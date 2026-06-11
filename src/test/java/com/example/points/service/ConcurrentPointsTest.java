package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.AdjustRequest;
import com.example.points.dto.PointsEventRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.service.impl.PointsEventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cross-cutting concurrency regression tests for PointsEventServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class ConcurrentPointsTest {

    @InjectMocks
    private PointsEventServiceImpl pointsEventService;

    @Mock private PointsAccountService accountService;
    @Mock private PointsFlowService flowService;
    @Mock private RuleEngine ruleEngine;
    @Mock private PointsAccountMapper accountMapper;
    @Mock private PointsFlowMapper flowMapper;
    @Mock private BlacklistService blacklistService;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private BudgetPoolService budgetPoolService;
    @Mock private RiskControlService riskControlService;
    @Mock private CircuitBreakerService circuitBreakerService;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(budgetPoolService.isPoolActiveAndValid(any())).thenReturn(true);
        lenient().when(riskControlService.evaluateInTransaction(anyLong(), anyLong(), any(), anyLong()))
                .thenReturn(java.util.Collections.emptyList());
    }

    @Test
    void testAdjust_ConcurrentLockContention_ThrowsException() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-contention");

        assertThrows(BusinessException.class, () -> pointsEventService.adjust(request));
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
    }

    @Test
    void testAdjust_DuplicateEventIdInsideLock_ReturnsExisting() throws Exception {
        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("adj-dup-inside");
        existingFlow.setPointsChange(50L);

        when(flowService.checkIdempotent("adj-dup-inside")).thenReturn(existingFlow);

        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-dup-inside");

        PointsFlow result = pointsEventService.adjust(request);

        assertNotNull(result);
        assertEquals("adj-dup-inside", result.getEventId());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
    }

    @Test
    void testProcessEvent_FlowRecordsAccurateAfterConcurrentChange() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("proc-concurrent");
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("proc-concurrent")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(200L); // Initial value read by getOrCreateAccount
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
        when(ruleEngine.calculatePoints(eq(request), any(PointsAccount.class))).thenReturn(100L);
        when(accountMapper.addPoints(1001L, 100L)).thenReturn(1);

        // After addPoints, re-read returns 250 (another concurrent operation added 50 more)
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(250L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.processEvent(request);

        // Flow uses re-read values: beforePoints = 250 - 100 = 150, afterPoints = 250
        assertNotNull(result);
        assertEquals(150L, result.getBeforePoints());
        assertEquals(250L, result.getAfterPoints());
    }

    @Test
    void testRefund_FlowRecordsAccurateAfterConcurrentChange() throws Exception {
        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-CONC");
        request.setEventId("refund-concurrent");
        request.setMemberId(1001L);

        when(flowService.checkIdempotent("refund-concurrent")).thenReturn(null);

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        originalFlow.setBizOrderNo("ORD-CONC");
        originalFlow.setEventType("PURCHASE");
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

        // After addPoints, re-read returns 700 (another operation already added points)
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(700L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.refund(request);

        // beforePoints = 700 - 200 = 500, afterPoints = 700
        assertEquals(500L, result.getBeforePoints());
        assertEquals(700L, result.getAfterPoints());
    }

    @Test
    void testAdjust_AddPoints_AccurateFlowRecord() {
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-add-accurate");
        request.setOperator("admin");

        when(flowService.checkIdempotent("adj-add-accurate")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(100L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 50L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(150L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.adjust(request);

        assertEquals(50L, result.getPointsChange());
        assertEquals(100L, result.getBeforePoints()); // 150 - 50 = 100
        assertEquals(150L, result.getAfterPoints());
    }

    @Test
    void testAdjust_DeductPoints_AccurateFlowRecord() {
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(-30L);
        request.setEventId("adj-ded-accurate");
        request.setOperator("admin");

        when(flowService.checkIdempotent("adj-ded-accurate")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(100L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.deductPoints(1001L, 30L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(70L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.adjust(request);

        assertEquals(-30L, result.getPointsChange());
        assertEquals(100L, result.getBeforePoints()); // 70 - (-30) = 100
        assertEquals(70L, result.getAfterPoints());
    }

    @Test
    void testAdjust_LockReleasedOnException() throws Exception {
        // Ensure isHeldByCurrentThread returns true so the finally block calls unlock()
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(flowService.checkIdempotent("adj-except")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(100L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 50L)).thenThrow(new RuntimeException("DB error"));

        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-except");

        assertThrows(RuntimeException.class, () -> pointsEventService.adjust(request));

        // Verify lock was released in finally block
        verify(rLock).unlock();
    }

    @Test
    void testConcurrentIssuance_BudgetConservation() {
        // Simulate: reserve succeeds but in-transaction risk check finds exhaustion
        // Budget should be released before throwing
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("evt-conserve");
        request.setEventType("ACTIVITY");
        request.setMemberId(1001L);
        request.setBudgetPoolId(1L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
        when(flowService.checkIdempotent("evt-conserve")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
        when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(1000L);
        when(accountMapper.addPoints(1001L, 1000L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(1000L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        // BUDGET_EXHAUSTION breached in-transaction
        when(riskControlService.evaluateInTransaction(eq(1001L), eq(1L), any(), eq(1000L)))
                .thenReturn(java.util.List.of("BUDGET_EXHAUSTION"));

        assertThrows(BusinessException.class, () -> pointsEventService.processEvent(request));

        // Budget must be released to maintain conservation
        verify(budgetPoolService).releaseBudget(1L, 1000L);
        verify(circuitBreakerService).recordTrip(1L, true);
    }
}
