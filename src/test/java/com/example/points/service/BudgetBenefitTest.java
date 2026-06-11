package com.example.points.service;

import com.example.points.common.BusinessException;
import com.example.points.dto.RedeemRequest;
import com.example.points.entity.*;
import com.example.points.mapper.*;
import com.example.points.service.impl.BenefitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetBenefitTest {

    @InjectMocks
    private BenefitServiceImpl benefitService;

    @Mock private BenefitMapper benefitMapper;
    @Mock private ExchangeRecordMapper exchangeRecordMapper;
    @Mock private PointsAccountMapper accountMapper;
    @Mock private PointsFlowMapper flowMapper;
    @Mock private PointsFlowService flowService;
    @Mock private PointsAccountService accountService;
    @Mock private BlacklistService blacklistService;
    @Mock private AuditLogService auditLogService;
    @Mock private MemberLevelMapper memberLevelMapper;
    @Mock private RedissonClient redissonClient;
    @Mock private BudgetPoolService budgetPoolService;
    @Mock private RiskControlService riskControlService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void testRedeem_WithBudgetPool_Success() {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-001");
        request.setBudgetPoolId(1L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("redeem-001")).thenReturn(null);

        Benefit benefit = new Benefit();
        benefit.setId(1L);
        benefit.setBenefitName("10元优惠券");
        benefit.setPointsCost(500L);
        benefit.setStatus(1);
        benefit.setAvailableStock(100);
        when(benefitMapper.selectById(1L)).thenReturn(benefit);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(1000L);
        account.setLevelId(2L);
        account.setStatus(1);
        when(accountService.getAccount(1001L)).thenReturn(account);

        BudgetPool pool = new BudgetPool();
        pool.setId(1L);
        pool.setStatus(1);
        when(budgetPoolService.getPool(1L)).thenReturn(pool);
        when(budgetPoolService.isPoolValidFor(any(), eq(2L))).thenReturn(true);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);

        when(accountMapper.deductPoints(1001L, 500L)).thenReturn(1);
        when(benefitMapper.decrementStock(1L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(500L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        when(exchangeRecordMapper.insert(any())).thenReturn(1);

        ExchangeRecord result = benefitService.redeem(request);

        assertNotNull(result);
        assertEquals(1L, result.getBudgetPoolId());
        verify(budgetPoolService).reserveBudget(1L, 500L);
    }

    @Test
    void testRedeem_BudgetExhausted_RollsBack() {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-002");
        request.setBudgetPoolId(1L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("redeem-002")).thenReturn(null);

        Benefit benefit = new Benefit();
        benefit.setId(1L);
        benefit.setBenefitName("10元优惠券");
        benefit.setPointsCost(500L);
        benefit.setStatus(1);
        benefit.setAvailableStock(100);
        when(benefitMapper.selectById(1L)).thenReturn(benefit);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(1000L);
        account.setLevelId(2L);
        account.setStatus(1);
        when(accountService.getAccount(1001L)).thenReturn(account);

        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
        BudgetPool pool = new BudgetPool();
        pool.setId(1L);
        when(budgetPoolService.getPool(1L)).thenReturn(pool);
        when(budgetPoolService.isPoolValidFor(any(), eq(2L))).thenReturn(true);

        doThrow(new BusinessException("预算池额度不足或已超限"))
                .when(budgetPoolService).reserveBudget(1L, 500L);

        assertThrows(BusinessException.class, () -> benefitService.redeem(request));
        verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
    }

    @Test
    void testRefundExchange_WithBudgetPool_RestoresBudget() {
        ExchangeRecord record = new ExchangeRecord();
        record.setId(1L);
        record.setMemberId(1001L);
        record.setBenefitId(1L);
        record.setPointsCost(500L);
        record.setStatus(1);
        record.setBudgetPoolId(1L);

        when(flowService.checkIdempotent("refund-ex-001")).thenReturn(null);
        when(exchangeRecordMapper.selectOne(any())).thenReturn(record);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);
        when(benefitMapper.incrementStock(1L)).thenReturn(1);
        when(exchangeRecordMapper.updateById(any())).thenReturn(1);

        benefitService.refundExchange("ORDER-001", "refund-ex-001", "admin");

        verify(budgetPoolService).releaseBudget(1L, 500L);
    }
}
