package com.membership.points.service;

import com.membership.points.common.enums.RedemptionStatusEnum;
import com.membership.points.common.exception.BusinessException;
import com.membership.points.common.result.Result;
import com.membership.points.dto.request.RefundRequest;
import com.membership.points.entity.BenefitInventory;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.RedemptionOrder;
import com.membership.points.mapper.BenefitInventoryMapper;
import com.membership.points.mapper.BenefitMapper;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.mapper.RedemptionOrderMapper;
import com.membership.points.service.impl.RefundServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    private RedemptionOrderMapper redemptionOrderMapper;

    @Mock
    private BenefitMapper benefitMapper;

    @Mock
    private BenefitInventoryMapper benefitInventoryMapper;

    @Mock
    private PointsAccountService pointsAccountService;

    @Mock
    private PointsBatchMapper pointsBatchMapper;

    @Mock
    private PointsTransactionMapper pointsTransactionMapper;

    @Mock
    private BlacklistService blacklistService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private RefundServiceImpl refundService;

    private RedemptionOrder order;
    private RefundRequest refundRequest;
    private BenefitInventory inventory;
    private PointsAccount account;

    @BeforeEach
    void setUp() {
        // Set the @Value field
        ReflectionTestUtils.setField(refundService, "maxRefundDays", 30);

        order = new RedemptionOrder();
        order.setId(1L);
        order.setOrderNo("ORD-20240101-001");
        order.setMemberId(1L);
        order.setBenefitId(10L);
        order.setInventoryId(100L);
        order.setQuantity(1);
        order.setPointsCost(200);
        order.setStatus(RedemptionStatusEnum.COMPLETED.getCode());
        order.setCompletedAt(LocalDateTime.now().minusDays(5));
        order.setCreatedAt(LocalDateTime.now().minusDays(5));

        refundRequest = new RefundRequest();
        refundRequest.setReason("不需要了");
        refundRequest.setOperator("admin");

        inventory = new BenefitInventory();
        inventory.setId(100L);
        inventory.setSkuCode("SKU-001");
        inventory.setAvailableStock(9);
        inventory.setVersion(1);

        account = new PointsAccount();
        account.setId(1L);
        account.setMemberId(1L);
        account.setAvailablePoints(300L);
        account.setFrozenPoints(0L);
        account.setVersion(1);
    }

    @Test
    void testRefund_success() {
        // Arrange: order completed 5 days ago, not blacklisted
        when(redemptionOrderMapper.selectById(1L)).thenReturn(order);
        when(blacklistService.isBlockedForEarn(1L)).thenReturn(false);
        when(redemptionOrderMapper.updateById(any(RedemptionOrder.class))).thenReturn(1);
        when(benefitInventoryMapper.selectById(100L)).thenReturn(inventory);
        when(benefitInventoryMapper.updateById(any(BenefitInventory.class))).thenReturn(1);
        when(pointsTransactionMapper.selectOne(any())).thenReturn(null);
        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);
        when(pointsTransactionMapper.insert(any())).thenReturn(1);
        when(pointsBatchMapper.insert(any())).thenReturn(1);

        // Act
        Result<?> result = refundService.refundOrder(1L, refundRequest);

        // Assert
        assertNotNull(result);
        assertEquals(200, result.getCode());

        // Verify stock returned
        verify(benefitInventoryMapper).updateById(any(BenefitInventory.class));

        // Verify points returned
        verify(pointsAccountService).addPoints(eq(1L), eq(200L), eq(1));

        // Verify order status updated to REFUNDED
        assertEquals(RedemptionStatusEnum.REFUNDED.getCode(), order.getStatus());
        verify(redemptionOrderMapper).updateById(any(RedemptionOrder.class));
    }

    @Test
    void testRefund_orderNotCompleted() {
        // Arrange: order status is PENDING
        order.setStatus(RedemptionStatusEnum.PENDING.getCode());
        when(redemptionOrderMapper.selectById(1L)).thenReturn(order);

        // Act & Assert
        assertThrows(BusinessException.class, () -> refundService.refundOrder(1L, refundRequest));

        // Verify no stock or points operations
        verify(benefitInventoryMapper, never()).updateById(any());
        verify(pointsAccountService, never()).addPoints(anyLong(), anyLong(), anyInt());
    }

    @Test
    void testRefund_exceedMaxDays() {
        // Arrange: order completed 35 days ago, exceeding the 30-day limit
        order.setCompletedAt(LocalDateTime.now().minusDays(35));
        when(redemptionOrderMapper.selectById(1L)).thenReturn(order);

        // Act & Assert
        assertThrows(BusinessException.class, () -> refundService.refundOrder(1L, refundRequest));

        // Verify no stock or points operations
        verify(benefitInventoryMapper, never()).updateById(any());
        verify(pointsAccountService, never()).addPoints(anyLong(), anyLong(), anyInt());
    }

    @Test
    void testRefund_blacklisted() {
        // Arrange: member is blacklisted
        when(redemptionOrderMapper.selectById(1L)).thenReturn(order);
        when(blacklistService.isBlockedForEarn(1L)).thenReturn(true);
        when(redemptionOrderMapper.updateById(any(RedemptionOrder.class))).thenReturn(1);
        when(benefitInventoryMapper.selectById(100L)).thenReturn(inventory);
        when(benefitInventoryMapper.updateById(any(BenefitInventory.class))).thenReturn(1);

        // Act
        Result<?> result = refundService.refundOrder(1L, refundRequest);

        // Assert
        assertNotNull(result);
        assertEquals(200, result.getCode());

        // Verify stock IS returned
        verify(benefitInventoryMapper).updateById(any(BenefitInventory.class));

        // Verify points are NOT returned (blacklisted)
        verify(pointsAccountService, never()).addPoints(anyLong(), anyLong(), anyInt());

        // Verify order status is still updated to REFUNDED
        assertEquals(RedemptionStatusEnum.REFUNDED.getCode(), order.getStatus());
    }
}
