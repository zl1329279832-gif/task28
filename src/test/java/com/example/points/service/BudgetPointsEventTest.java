package com.example.points.service;

import com.example.points.common.BusinessException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetPointsEventTest {

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
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock rLock;
    @Mock private RBucket<Object> rBucket;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        lenient().when(redissonClient.getBucket(anyString())).thenReturn(rBucket);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void testProcessEvent_WithBudgetPool_Success() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("evt-001");
        request.setEventType("ACTIVITY");
        request.setMemberId(1001L);
        request.setBudgetPoolId(1L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
        when(flowService.checkIdempotent("evt-001")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
        when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(500L);
        when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(500L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.processEvent(request);

        assertNotNull(result);
        assertEquals(1L, result.getBudgetPoolId());
        verify(budgetPoolService).reserveBudget(1L, 500L);
        verify(riskControlService, atLeastOnce()).isCircuitBreakerAllowing(1L);
    }

    @Test
    void testProcessEvent_BudgetExhausted_Throws() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("evt-002");
        request.setEventType("ACTIVITY");
        request.setMemberId(1001L);
        request.setBudgetPoolId(1L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
        when(flowService.checkIdempotent("evt-002")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
        when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(500L);

        doThrow(new BusinessException("预算池额度不足或已超限"))
                .when(budgetPoolService).reserveBudget(1L, 500L);

        assertThrows(BusinessException.class, () -> pointsEventService.processEvent(request));
    }

    @Test
    void testProcessEvent_DuplicateWithBudget_NoDoubleReserve() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("evt-003");
        request.setEventType("ACTIVITY");
        request.setMemberId(1001L);
        request.setBudgetPoolId(1L);

        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("evt-003");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
        when(flowService.checkIdempotent("evt-003")).thenReturn(existingFlow);

        PointsFlow result = pointsEventService.processEvent(request);

        assertEquals(existingFlow, result);
        verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
    }

    @Test
    void testProcessEvent_CircuitBreakerOpen_Throws() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("evt-004");
        request.setEventType("ACTIVITY");
        request.setMemberId(1001L);
        request.setBudgetPoolId(1L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(false);

        assertThrows(BusinessException.class, () -> pointsEventService.processEvent(request));
        verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
    }

    @Test
    void testProcessEvent_ZeroPointsWithBudget_NoReservation() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("evt-005");
        request.setEventType("CHECKIN");
        request.setMemberId(1001L);
        request.setBudgetPoolId(1L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
        when(flowService.checkIdempotent("evt-005")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
        when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(0L);

        PointsFlow result = pointsEventService.processEvent(request);

        assertNull(result);
        verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
    }

    @Test
    void testRefund_WithBudgetPool_RestoresBudget() {
        RefundRequest request = new RefundRequest();
        request.setEventId("refund-001");
        request.setMemberId(1001L);
        request.setBizOrderNo("ORDER-001");

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(500L);
        originalFlow.setBudgetPoolId(1L);

        when(flowService.checkIdempotent("refund-001")).thenReturn(null);
        when(flowMapper.selectOne(any())).thenReturn(originalFlow);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(1000L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.refund(request);

        assertNotNull(result);
        verify(budgetPoolService).releaseBudget(1L, 500L);
    }

    @Test
    void testProcessEvent_RuleVersionChange_BudgetStillWorks() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("evt-006");
        request.setEventType("ACTIVITY");
        request.setMemberId(1001L);
        request.setBudgetPoolId(1L);
        request.setActivityCode("NEW_ACTIVITY");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
        when(flowService.checkIdempotent("evt-006")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
        when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(300L);
        when(accountMapper.addPoints(1001L, 300L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(300L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.processEvent(request);

        assertNotNull(result);
        assertEquals(300L, result.getPointsChange());
        verify(budgetPoolService).reserveBudget(1L, 300L);
    }
}
