package com.example.points.service;

import com.example.points.entity.RiskFreezeOrder;
import com.example.points.entity.PointsFreeze;
import com.example.points.enums.ReviewStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.mapper.RiskFreezeOrderMapper;
import com.example.points.scheduled.BudgetRiskTask;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetRiskTaskTest {

    @InjectMocks
    private BudgetRiskTask budgetRiskTask;

    @Mock private BudgetPoolMapper budgetPoolMapper;
    @Mock private RiskFreezeOrderMapper riskFreezeOrderMapper;
    @Mock private PointsFreezeMapper pointsFreezeMapper;
    @Mock private CircuitBreakerService circuitBreakerService;
    @Mock private PointsFreezeService pointsFreezeService;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void testResetDailyCounters_Success() {
        when(budgetPoolMapper.resetDailyUsed(any(LocalDate.class))).thenReturn(5);

        budgetRiskTask.resetDailyBudgetCounters();

        verify(budgetPoolMapper).resetDailyUsed(any(LocalDate.class));
    }

    @Test
    void testResetDailyCounters_LockNotAcquired_Skips() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        budgetRiskTask.resetDailyBudgetCounters();

        verify(budgetPoolMapper, never()).resetDailyUsed(any());
    }

    @Test
    void testResetMonthlyCounters_Success() {
        when(budgetPoolMapper.resetMonthlyUsed(any(LocalDate.class))).thenReturn(3);

        budgetRiskTask.resetMonthlyBudgetCounters();

        verify(budgetPoolMapper).resetMonthlyUsed(any(LocalDate.class));
    }

    @Test
    void testCircuitBreakerRecovery_Success() {
        budgetRiskTask.circuitBreakerRecoveryCheck();

        verify(circuitBreakerService).processRecoveryChecks();
    }

    @Test
    void testReleaseExpiredRiskFreezes_Success() {
        RiskFreezeOrder order = new RiskFreezeOrder();
        order.setId(1L);
        order.setFreezeOrderNo("RFO_001");
        order.setPointsFreezeId(100L);
        order.setReviewStatus(ReviewStatus.PENDING.getCode());

        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(100L);
        freeze.setFreezeNo("RISK_001");

        when(riskFreezeOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(riskFreezeOrderMapper.selectById(1L)).thenReturn(order);
        when(pointsFreezeMapper.selectById(100L)).thenReturn(freeze);

        // Mock TransactionTemplate to execute the callback
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(riskFreezeOrderMapper.updateById(any())).thenReturn(1);

        budgetRiskTask.releaseExpiredRiskFreezes();

        verify(pointsFreezeService).unfreeze("RISK_001");
    }

    @Test
    void testReleaseExpiredRiskFreezes_AlreadyProcessed_Skips() {
        RiskFreezeOrder order = new RiskFreezeOrder();
        order.setId(1L);
        order.setFreezeOrderNo("RFO_001");
        order.setReviewStatus(ReviewStatus.PENDING.getCode());

        RiskFreezeOrder alreadyProcessed = new RiskFreezeOrder();
        alreadyProcessed.setId(1L);
        alreadyProcessed.setReviewStatus(ReviewStatus.APPROVED.getCode());

        when(riskFreezeOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(riskFreezeOrderMapper.selectById(1L)).thenReturn(alreadyProcessed);

        budgetRiskTask.releaseExpiredRiskFreezes();

        verify(pointsFreezeService, never()).unfreeze(anyString());
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void testUpdateBudgetPoolStatus_MarksExpiredAndExhausted() {
        when(budgetPoolMapper.markExpiredPools()).thenReturn(2);
        when(budgetPoolMapper.markExhaustedPools()).thenReturn(1);

        budgetRiskTask.updateBudgetPoolStatus();

        verify(budgetPoolMapper).markExpiredPools();
        verify(budgetPoolMapper).markExhaustedPools();
    }
}
