package com.membership.points.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membership.points.common.exception.BlacklistedException;
import com.membership.points.common.exception.InsufficientPointsException;
import com.membership.points.common.exception.InsufficientStockException;
import com.membership.points.common.result.Result;
import com.membership.points.common.util.RedisLockUtil;
import com.membership.points.dto.request.RedeemPointsRequest;
import com.membership.points.dto.response.RedemptionOrderResponse;
import com.membership.points.entity.Benefit;
import com.membership.points.entity.BenefitInventory;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.mapper.BenefitInventoryMapper;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.mapper.RedemptionOrderMapper;
import com.membership.points.service.impl.RedemptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedemptionServiceTest {

    @Mock
    private IdempotentService idempotentService;

    @Mock
    private BlacklistService blacklistService;

    @Mock
    private BenefitService benefitService;

    @Mock
    private PointsAccountService pointsAccountService;

    @Mock
    private MemberLevelService memberLevelService;

    @Mock
    private BenefitInventoryMapper benefitInventoryMapper;

    @Mock
    private PointsBatchMapper pointsBatchMapper;

    @Mock
    private PointsTransactionMapper pointsTransactionMapper;

    @Mock
    private RedemptionOrderMapper redemptionOrderMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CacheService cacheService;

    @Mock
    private RedisLockUtil redisLockUtil;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RedemptionServiceImpl redemptionService;

    private RedeemPointsRequest request;
    private Benefit benefit;
    private BenefitInventory inventory;
    private PointsAccount account;

    @BeforeEach
    void setUp() {
        request = new RedeemPointsRequest();
        request.setMemberId(1L);
        request.setBenefitId(10L);
        request.setSkuCode("SKU-001");
        request.setQuantity(1);
        request.setIdempotentKey("redeem-key-001");

        benefit = new Benefit();
        benefit.setId(10L);
        benefit.setBenefitName("测试权益");
        benefit.setPointsCost(100);
        benefit.setEnabled(1);

        inventory = new BenefitInventory();
        inventory.setId(1L);
        inventory.setBenefitId(10L);
        inventory.setSkuCode("SKU-001");
        inventory.setAvailableStock(10);
        inventory.setVersion(1);

        account = new PointsAccount();
        account.setId(1L);
        account.setMemberId(1L);
        account.setAvailablePoints(500L);
        account.setFrozenPoints(0L);
        account.setVersion(1);
    }

    @SuppressWarnings("unchecked")
    private void setupTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void testRedeem_success() throws Exception {
        // Arrange
        when(idempotentService.checkAndMark("redeem-key-001", "REDEEM")).thenReturn(true);
        when(blacklistService.isBlockedForRedeem(1L)).thenReturn(false);
        when(benefitService.getBenefitById(10L)).thenReturn(benefit);
        when(benefitService.getInventoryBySkuCode("SKU-001")).thenReturn(inventory);
        when(redisLockUtil.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        setupTransactionTemplate();

        // Inside transaction
        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);
        when(benefitInventoryMapper.selectById(1L)).thenReturn(inventory);
        when(benefitInventoryMapper.updateById(any(BenefitInventory.class))).thenReturn(1);

        PointsBatch batch = new PointsBatch();
        batch.setId(1L);
        batch.setMemberId(1L);
        batch.setRemainingPoints(500L);
        batch.setFrozenPoints(0L);
        when(pointsBatchMapper.selectActiveBatchesByMemberId(1L)).thenReturn(List.of(batch));
        when(pointsBatchMapper.updateById(any(PointsBatch.class))).thenReturn(1);
        when(pointsTransactionMapper.insert(any())).thenReturn(1);
        when(redemptionOrderMapper.insert(any())).thenReturn(1);

        // Act
        Result<RedemptionOrderResponse> result = redemptionService.redeem(request);

        // Assert
        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(pointsAccountService).deductPoints(eq(1L), eq(100L), eq(1));
        verify(benefitInventoryMapper).updateById(any(BenefitInventory.class));
        verify(redemptionOrderMapper).insert(any());
        verify(redisLockUtil).unlock(anyString());
    }

    @Test
    void testRedeem_insufficientPoints() {
        // Arrange: account has only 50 points, benefit costs 100
        account.setAvailablePoints(50L);

        when(idempotentService.checkAndMark("redeem-key-001", "REDEEM")).thenReturn(true);
        when(blacklistService.isBlockedForRedeem(1L)).thenReturn(false);
        when(benefitService.getBenefitById(10L)).thenReturn(benefit);
        when(benefitService.getInventoryBySkuCode("SKU-001")).thenReturn(inventory);
        when(redisLockUtil.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        setupTransactionTemplate();
        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);

        // Act & Assert
        assertThrows(InsufficientPointsException.class, () -> redemptionService.redeem(request));
        verify(redisLockUtil).unlock(anyString());
    }

    @Test
    void testRedeem_insufficientStock() {
        // Arrange: inventory has 0 available stock
        inventory.setAvailableStock(0);

        when(idempotentService.checkAndMark("redeem-key-001", "REDEEM")).thenReturn(true);
        when(blacklistService.isBlockedForRedeem(1L)).thenReturn(false);
        when(benefitService.getBenefitById(10L)).thenReturn(benefit);
        when(benefitService.getInventoryBySkuCode("SKU-001")).thenReturn(inventory);

        // Act & Assert
        assertThrows(InsufficientStockException.class, () -> redemptionService.redeem(request));
    }

    @Test
    void testRedeem_blacklisted() {
        // Arrange
        when(idempotentService.checkAndMark("redeem-key-001", "REDEEM")).thenReturn(true);
        when(blacklistService.isBlockedForRedeem(1L)).thenReturn(true);

        // Act & Assert
        assertThrows(BlacklistedException.class, () -> redemptionService.redeem(request));

        // Verify no further business logic was invoked
        verify(benefitService, never()).getBenefitById(anyLong());
        verify(pointsAccountService, never()).deductPoints(anyLong(), anyLong(), anyInt());
    }

    @Test
    void testRedeem_fifoBatchDeduction() throws Exception {
        // Arrange: 3 batches with remaining 30, 50, 80. Redeem 100 points.
        when(idempotentService.checkAndMark("redeem-key-001", "REDEEM")).thenReturn(true);
        when(blacklistService.isBlockedForRedeem(1L)).thenReturn(false);
        when(benefitService.getBenefitById(10L)).thenReturn(benefit);
        when(benefitService.getInventoryBySkuCode("SKU-001")).thenReturn(inventory);
        when(redisLockUtil.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        setupTransactionTemplate();

        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);
        when(benefitInventoryMapper.selectById(1L)).thenReturn(inventory);
        when(benefitInventoryMapper.updateById(any(BenefitInventory.class))).thenReturn(1);

        PointsBatch batch1 = new PointsBatch();
        batch1.setId(1L);
        batch1.setMemberId(1L);
        batch1.setRemainingPoints(30L);
        batch1.setFrozenPoints(0L);

        PointsBatch batch2 = new PointsBatch();
        batch2.setId(2L);
        batch2.setMemberId(1L);
        batch2.setRemainingPoints(50L);
        batch2.setFrozenPoints(0L);

        PointsBatch batch3 = new PointsBatch();
        batch3.setId(3L);
        batch3.setMemberId(1L);
        batch3.setRemainingPoints(80L);
        batch3.setFrozenPoints(0L);

        when(pointsBatchMapper.selectActiveBatchesByMemberId(1L))
                .thenReturn(Arrays.asList(batch1, batch2, batch3));
        when(pointsBatchMapper.updateById(any(PointsBatch.class))).thenReturn(1);
        when(pointsTransactionMapper.insert(any())).thenReturn(1);
        when(redemptionOrderMapper.insert(any())).thenReturn(1);

        // Act
        Result<RedemptionOrderResponse> result = redemptionService.redeem(request);

        // Assert
        assertNotNull(result);
        assertEquals(200, result.getCode());

        // Batch1: 30 fully consumed => remaining=0, expired=1
        assertEquals(0L, batch1.getRemainingPoints());
        assertEquals(1, batch1.getExpired());

        // Batch2: 50 fully consumed => remaining=0, expired=1
        assertEquals(0L, batch2.getRemainingPoints());
        assertEquals(1, batch2.getExpired());

        // Batch3: only 20 consumed (100-30-50=20) => remaining=60
        assertEquals(60L, batch3.getRemainingPoints());

        verify(pointsBatchMapper, times(3)).updateById(any(PointsBatch.class));
        verify(pointsAccountService).deductPoints(eq(1L), eq(100L), eq(1));
    }
}
