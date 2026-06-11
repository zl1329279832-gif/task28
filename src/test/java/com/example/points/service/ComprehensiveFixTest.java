package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.PointsEventRequest;
import com.example.points.dto.RedeemRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.*;
import com.example.points.enums.CircuitBreakerStatus;
import com.example.points.mapper.*;
import com.example.points.service.impl.BenefitServiceImpl;
import com.example.points.service.impl.CircuitBreakerServiceImpl;
import com.example.points.service.impl.PointsEventServiceImpl;
import com.example.points.service.impl.RiskControlServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests covering:
 * - Concurrent issuance with budget conservation
 * - Budget exhaustion scenarios
 * - Duplicate eventId across services
 * - Activity rule versioning with budget
 * - Benefit refund budget restoration
 * - Circuit breaker recovery
 */
@ExtendWith(MockitoExtension.class)
class ComprehensiveFixTest {

    // =====================================================================
    // 1. Concurrent issuance + budget conservation
    // =====================================================================
    @Nested
    @DisplayName("Concurrent Issuance & Budget Conservation")
    class ConcurrentIssuanceBudgetTest {

        @InjectMocks
        private PointsEventServiceImpl service;

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

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
        }

        @Test
        @DisplayName("Lock contention blocks concurrent issuance - second request fails fast")
        void testConcurrentIssuance_LockContention_SecondRequestFails() throws Exception {
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
            when(blacklistService.isBlacklisted(anyLong())).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(anyLong())).thenReturn(true);
            when(flowService.checkIdempotent(anyString())).thenReturn(null);

            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("concurrent-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            assertThrows(BusinessException.class, () -> service.processEvent(request));
            verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Budget reserved and points added atomically in same transaction")
        void testBudgetReserveAndPointsAtomic() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("atomic-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            when(flowService.checkIdempotent("atomic-001")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            account.setStatus(1);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(200L);
            when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(200L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            PointsFlow result = service.processEvent(request);

            assertNotNull(result);
            // Verify order: reserveBudget before addPoints (both in same transaction)
            var inOrder = inOrder(budgetPoolService, accountMapper, flowService);
            inOrder.verify(budgetPoolService).reserveBudget(1L, 200L);
            inOrder.verify(accountMapper).addPoints(1001L, 200L);
            inOrder.verify(flowService).saveFlow(any());
        }

        @Test
        @DisplayName("Budget reservation failure prevents points addition")
        void testBudgetReservationFailure_NoPointsAdded() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("budget-fail-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            when(flowService.checkIdempotent("budget-fail-001")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            account.setStatus(1);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(500L);
            doThrow(new BusinessException("预算池额度不足或已超限"))
                    .when(budgetPoolService).reserveBudget(1L, 500L);

            assertThrows(BusinessException.class, () -> service.processEvent(request));
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
            verify(flowService, never()).saveFlow(any());
        }

        @Test
        @DisplayName("Blacklist check inside lock prevents race condition")
        void testBlacklistCheckInsideLock_PreventsRace() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("blacklist-race-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            // First check (pre-lock) passes, second check (in-lock) detects blacklist
            when(blacklistService.isBlacklisted(1001L)).thenReturn(false).thenReturn(true);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            when(flowService.checkIdempotent("blacklist-race-001")).thenReturn(null);

            assertThrows(BusinessException.class, () -> service.processEvent(request));
            verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Circuit breaker check inside lock prevents race condition")
        void testCircuitBreakerCheckInsideLock_PreventsRace() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("cb-race-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            // First check (pre-lock) passes, second check (in-lock) detects open breaker
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true).thenReturn(false);
            when(flowService.checkIdempotent("cb-race-001")).thenReturn(null);

            assertThrows(BusinessException.class, () -> service.processEvent(request));
            verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
        }
    }

    // =====================================================================
    // 2. Budget exhaustion scenarios
    // =====================================================================
    @Nested
    @DisplayName("Budget Exhaustion Scenarios")
    class BudgetExhaustionTest {

        @InjectMocks
        private PointsEventServiceImpl service;

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

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
        }

        @Test
        @DisplayName("Daily cap exhausted blocks further issuance")
        void testDailyCapExhausted_BlocksIssuance() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("daily-cap-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            when(flowService.checkIdempotent("daily-cap-001")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            account.setStatus(1);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(100L);

            // reserveBudget checks daily_cap and rejects
            doThrow(new BusinessException("预算池额度不足或已超限"))
                    .when(budgetPoolService).reserveBudget(1L, 100L);

            assertThrows(BusinessException.class, () -> service.processEvent(request));
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Zero points calculated does not reserve budget")
        void testZeroPoints_NoBudgetReservation() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("zero-pts-001");
            request.setEventType("CHECKIN");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            when(flowService.checkIdempotent("zero-pts-001")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            account.setStatus(1);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(0L);

            PointsFlow result = service.processEvent(request);

            assertNull(result);
            verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
        }
    }

    // =====================================================================
    // 3. Duplicate eventId across services
    // =====================================================================
    @Nested
    @DisplayName("Duplicate EventId Handling")
    class DuplicateEventIdTest {

        @InjectMocks
        private PointsEventServiceImpl service;

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

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
        }

        @Test
        @DisplayName("Duplicate eventId in processEvent - pre-lock check returns existing")
        void testDuplicateEventId_PreLockCheck() {
            PointsFlow existing = new PointsFlow();
            existing.setEventId("dup-001");
            existing.setPointsChange(100L);
            existing.setBudgetPoolId(1L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            when(flowService.checkIdempotent("dup-001")).thenReturn(existing);

            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("dup-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            PointsFlow result = service.processEvent(request);

            assertSame(existing, result);
            verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Duplicate eventId in processEvent - in-lock double-check catches race")
        void testDuplicateEventId_InLockDoubleCheck() {
            PointsFlow existing = new PointsFlow();
            existing.setEventId("dup-002");
            existing.setPointsChange(100L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            // Pre-lock: null, in-lock: existing
            when(flowService.checkIdempotent("dup-002")).thenReturn(null).thenReturn(existing);

            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("dup-002");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);

            PointsFlow result = service.processEvent(request);

            assertSame(existing, result);
            verify(budgetPoolService, never()).reserveBudget(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Duplicate eventId in refund - no double budget release")
        void testDuplicateRefund_NoDoubleBudgetRelease() {
            PointsFlow existingRefund = new PointsFlow();
            existingRefund.setEventId("refund-dup-001");
            existingRefund.setPointsChange(200L);

            when(flowService.checkIdempotent("refund-dup-001")).thenReturn(existingRefund);

            RefundRequest request = new RefundRequest();
            request.setEventId("refund-dup-001");
            request.setMemberId(1001L);
            request.setBizOrderNo("ORD-001");

            PointsFlow result = service.refund(request);

            assertSame(existingRefund, result);
            verify(budgetPoolService, never()).releaseBudget(anyLong(), anyLong());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Duplicate eventId in adjust - no double operation")
        void testDuplicateAdjust_NoDoubleOperation() {
            PointsFlow existing = new PointsFlow();
            existing.setEventId("adj-dup-001");
            existing.setPointsChange(50L);

            when(flowService.checkIdempotent("adj-dup-001")).thenReturn(existing);

            com.example.points.dto.AdjustRequest request = new com.example.points.dto.AdjustRequest();
            request.setEventId("adj-dup-001");
            request.setMemberId(1001L);
            request.setPoints(50L);

            PointsFlow result = service.adjust(request);

            assertSame(existing, result);
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
            verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
        }
    }

    // =====================================================================
    // 4. Activity rule versioning with budget
    // =====================================================================
    @Nested
    @DisplayName("Activity Rule Versioning")
    class RuleVersioningTest {

        @InjectMocks
        private PointsEventServiceImpl service;

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

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
        }

        @Test
        @DisplayName("New rule version changes point calculation, budget reservation matches")
        void testRuleVersionChange_BudgetMatchesNewPoints() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("rule-v2-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setBudgetPoolId(1L);
            request.setActivityCode("DOUBLE_POINTS");

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            when(flowService.checkIdempotent("rule-v2-001")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(100L);
            account.setMonthlyEarned(0L);
            account.setStatus(1);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            // New rule version gives 1000 points instead of 500
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(1000L);
            when(accountMapper.addPoints(1001L, 1000L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(1100L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            PointsFlow result = service.processEvent(request);

            assertNotNull(result);
            assertEquals(1000L, result.getPointsChange());
            // Budget reservation must match calculated points, not old rule
            verify(budgetPoolService).reserveBudget(1L, 1000L);
        }

        @Test
        @DisplayName("Rule version change mid-flight - budget matches actually calculated points")
        void testRuleVersionChange_NoBudgetMismatch() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("rule-v3-001");
            request.setEventType("PURCHASE");
            request.setMemberId(1001L);
            request.setBudgetPoolId(2L);
            request.setAmount(10000L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(riskControlService.isCircuitBreakerAllowing(2L)).thenReturn(true);
            when(flowService.checkIdempotent("rule-v3-001")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            account.setStatus(1);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(50L);
            when(accountMapper.addPoints(1001L, 50L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(50L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            PointsFlow result = service.processEvent(request);

            // Budget reserved exactly matches calculated points
            verify(budgetPoolService).reserveBudget(2L, 50L);
            assertEquals(50L, result.getPointsChange());
            assertEquals(2L, result.getBudgetPoolId());
        }
    }

    // =====================================================================
    // 5. Benefit refund budget restoration
    // =====================================================================
    @Nested
    @DisplayName("Benefit Refund Budget Restoration")
    class BenefitRefundBudgetTest {

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
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
        }

        @Test
        @DisplayName("Benefit refund uses releaseBudget (restores daily/monthly counters)")
        void testRefundExchange_UsesReleaseBudget() {
            ExchangeRecord record = ExchangeRecord.builder()
                    .id(1L)
                    .memberId(1001L)
                    .benefitId(1L)
                    .pointsCost(500L)
                    .status(1)
                    .budgetPoolId(1L)
                    .bizOrderNo("BIZ-001")
                    .build();

            when(flowService.checkIdempotent("refund-budget-001")).thenReturn(null);
            when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);
            when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(1500L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            benefitService.refundExchange("BIZ-001", "refund-budget-001", "admin");

            // Must use releaseBudget (not restoreBudget) to restore daily/monthly counters
            verify(budgetPoolService).releaseBudget(1L, 500L);
            verify(budgetPoolService, never()).restoreBudget(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Benefit refund flow records budgetPoolId for audit")
        void testRefundExchange_FlowRecordsBudgetPoolId() {
            ExchangeRecord record = ExchangeRecord.builder()
                    .id(1L)
                    .memberId(1001L)
                    .benefitId(1L)
                    .pointsCost(500L)
                    .status(1)
                    .budgetPoolId(2L)
                    .bizOrderNo("BIZ-002")
                    .build();

            when(flowService.checkIdempotent("refund-audit-001")).thenReturn(null);
            when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);
            when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(1500L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            benefitService.refundExchange("BIZ-002", "refund-audit-001", "admin");

            ArgumentCaptor<PointsFlow> flowCaptor = ArgumentCaptor.forClass(PointsFlow.class);
            verify(flowService).saveFlow(flowCaptor.capture());
            assertEquals(2L, flowCaptor.getValue().getBudgetPoolId());
        }

        @Test
        @DisplayName("Benefit refund without budget pool skips budget release")
        void testRefundExchange_NoBudgetPool_SkipsRelease() {
            ExchangeRecord record = ExchangeRecord.builder()
                    .id(1L)
                    .memberId(1001L)
                    .benefitId(1L)
                    .pointsCost(300L)
                    .status(1)
                    .budgetPoolId(null) // No budget pool
                    .bizOrderNo("BIZ-003")
                    .build();

            when(flowService.checkIdempotent("refund-nopool-001")).thenReturn(null);
            when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);
            when(accountMapper.addPoints(1001L, 300L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(1300L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            benefitService.refundExchange("BIZ-003", "refund-nopool-001", "admin");

            verify(budgetPoolService, never()).releaseBudget(anyLong(), anyLong());
            verify(budgetPoolService, never()).restoreBudget(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Redeem uses reserveBudget (tracks daily/monthly caps)")
        void testRedeem_UsesReserveBudget() {
            RedeemRequest request = new RedeemRequest();
            request.setMemberId(1001L);
            request.setBenefitId(1L);
            request.setEventId("redeem-reserve-001");
            request.setBudgetPoolId(1L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(flowService.checkIdempotent("redeem-reserve-001")).thenReturn(null);

            Benefit benefit = new Benefit();
            benefit.setId(1L);
            benefit.setBenefitName("Coupon");
            benefit.setPointsCost(500L);
            benefit.setStatus(1);
            benefit.setAvailableStock(100);
            when(benefitMapper.selectById(1L)).thenReturn(benefit);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(1000L);
            account.setLevelId(1L);
            when(accountService.getAccount(1001L)).thenReturn(account);

            when(riskControlService.isCircuitBreakerAllowing(1L)).thenReturn(true);
            BudgetPool pool = new BudgetPool();
            pool.setId(1L);
            pool.setStatus(1);
            when(budgetPoolService.getPool(1L)).thenReturn(pool);
            when(budgetPoolService.isPoolValidFor(any(), eq(1L))).thenReturn(true);

            when(accountMapper.deductPoints(1001L, 500L)).thenReturn(1);
            when(benefitMapper.decrementStock(1L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(500L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            benefitService.redeem(request);

            // Must use reserveBudget (not consumeBudget) to track daily/monthly caps
            verify(budgetPoolService).reserveBudget(1L, 500L);
            verify(budgetPoolService, never()).consumeBudget(anyLong(), anyLong());
        }
    }

    // =====================================================================
    // 6. Points refund budget restoration
    // =====================================================================
    @Nested
    @DisplayName("Points Refund Budget Restoration")
    class PointsRefundBudgetTest {

        @InjectMocks
        private PointsEventServiceImpl service;

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

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
        }

        @Test
        @DisplayName("Refund uses releaseBudget to restore daily/monthly counters")
        void testRefund_UsesReleaseBudget() {
            PointsFlow originalFlow = new PointsFlow();
            originalFlow.setPointsChange(500L);
            originalFlow.setBudgetPoolId(1L);

            when(flowService.checkIdempotent("refund-release-001")).thenReturn(null);
            when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(500L);
            when(accountService.getAccount(1001L)).thenReturn(account);
            when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(1000L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            RefundRequest request = new RefundRequest();
            request.setEventId("refund-release-001");
            request.setMemberId(1001L);
            request.setBizOrderNo("ORD-001");

            PointsFlow result = service.refund(request);

            assertNotNull(result);
            // Must use releaseBudget (not restoreBudget) to restore daily/monthly counters
            verify(budgetPoolService).releaseBudget(1L, 500L);
            verify(budgetPoolService, never()).restoreBudget(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Refund flow records budgetPoolId from original flow")
        void testRefund_FlowRecordsBudgetPoolId() {
            PointsFlow originalFlow = new PointsFlow();
            originalFlow.setPointsChange(300L);
            originalFlow.setBudgetPoolId(5L);

            when(flowService.checkIdempotent("refund-audit-001")).thenReturn(null);
            when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(200L);
            when(accountService.getAccount(1001L)).thenReturn(account);
            when(accountMapper.addPoints(1001L, 300L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(500L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            RefundRequest request = new RefundRequest();
            request.setEventId("refund-audit-001");
            request.setMemberId(1001L);
            request.setBizOrderNo("ORD-002");

            PointsFlow result = service.refund(request);

            // Refund flow must carry budgetPoolId from original flow
            assertEquals(5L, result.getBudgetPoolId());
        }

        @Test
        @DisplayName("Refund without budget pool skips release")
        void testRefund_NoBudgetPool_SkipsRelease() {
            PointsFlow originalFlow = new PointsFlow();
            originalFlow.setPointsChange(200L);
            originalFlow.setBudgetPoolId(null); // No budget pool

            when(flowService.checkIdempotent("refund-nopool-001")).thenReturn(null);
            when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(100L);
            when(accountService.getAccount(1001L)).thenReturn(account);
            when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setMemberId(1001L);
            updatedAccount.setAvailablePoints(300L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            RefundRequest request = new RefundRequest();
            request.setEventId("refund-nopool-001");
            request.setMemberId(1001L);
            request.setBizOrderNo("ORD-003");

            service.refund(request);

            verify(budgetPoolService, never()).releaseBudget(anyLong(), anyLong());
        }
    }

    // =====================================================================
    // 7. Circuit breaker recovery
    // =====================================================================
    @Nested
    @DisplayName("Circuit Breaker Recovery")
    class CircuitBreakerRecoveryTest {

        @InjectMocks
        private CircuitBreakerServiceImpl circuitBreakerService;

        @Mock private CircuitBreakerMapper circuitBreakerMapper;
        @Mock private AuditLogService auditLogService;

        @Test
        @DisplayName("OPEN -> HALF_OPEN transition logs audit")
        void testOpenToHalfOpen_LogsAudit() {
            CircuitBreaker cb = new CircuitBreaker();
            cb.setId(1L);
            cb.setPoolId(10L);
            cb.setStatus(CircuitBreakerStatus.OPEN.name());

            when(circuitBreakerMapper.findOpenBreakersReadyForRecovery()).thenReturn(List.of(cb));
            when(circuitBreakerMapper.casTransition(1L, "OPEN", "HALF_OPEN")).thenReturn(1);
            when(circuitBreakerMapper.selectList(any())).thenReturn(Collections.emptyList());

            circuitBreakerService.processRecoveryChecks();

            verify(auditLogService).log(eq("CIRCUIT_BREAKER"), eq("AUTO_RECOVERY"),
                    eq("10"), eq("BUDGET_POOL"), eq("OPEN"), eq("HALF_OPEN"), eq("SYSTEM"), isNull());
        }

        @Test
        @DisplayName("HALF_OPEN -> CLOSED transition logs audit")
        void testHalfOpenToClosed_LogsAudit() {
            CircuitBreaker cb = new CircuitBreaker();
            cb.setId(1L);
            cb.setPoolId(10L);
            cb.setStatus(CircuitBreakerStatus.HALF_OPEN.name());
            cb.setHalfOpenCount(10);
            cb.setMaxTestRequests(10);

            when(circuitBreakerMapper.findOpenBreakersReadyForRecovery()).thenReturn(Collections.emptyList());
            when(circuitBreakerMapper.selectList(any())).thenReturn(List.of(cb));
            when(circuitBreakerMapper.closeFromHalfOpen(10L)).thenReturn(1);

            circuitBreakerService.processRecoveryChecks();

            verify(auditLogService).log(eq("CIRCUIT_BREAKER"), eq("AUTO_CLOSE"),
                    eq("10"), eq("BUDGET_POOL"), eq("HALF_OPEN"), eq("CLOSED"), eq("SYSTEM"), isNull());
        }

        @Test
        @DisplayName("HALF_OPEN with insufficient test requests does not close")
        void testHalfOpen_InsufficientTests_DoesNotClose() {
            CircuitBreaker cb = new CircuitBreaker();
            cb.setId(1L);
            cb.setPoolId(10L);
            cb.setStatus(CircuitBreakerStatus.HALF_OPEN.name());
            cb.setHalfOpenCount(5);
            cb.setMaxTestRequests(10);

            when(circuitBreakerMapper.findOpenBreakersReadyForRecovery()).thenReturn(Collections.emptyList());
            when(circuitBreakerMapper.selectList(any())).thenReturn(List.of(cb));

            circuitBreakerService.processRecoveryChecks();

            verify(circuitBreakerMapper, never()).closeFromHalfOpen(anyLong());
        }

        @Test
        @DisplayName("ManualClose records correct beforeValue in audit log")
        void testManualClose_CorrectAuditBeforeValue() {
            CircuitBreaker cb = new CircuitBreaker();
            cb.setId(1L);
            cb.setPoolId(10L);
            cb.setStatus(CircuitBreakerStatus.OPEN.name());

            when(circuitBreakerMapper.selectByPoolId(10L)).thenReturn(cb);
            when(circuitBreakerMapper.casTransition(1L, "OPEN", "CLOSED")).thenReturn(1);
            when(circuitBreakerMapper.updateById(any())).thenReturn(1);

            circuitBreakerService.manualClose(10L, "admin");

            // beforeValue must be "OPEN" (original status), not "CLOSED"
            verify(auditLogService).log(eq("CIRCUIT_BREAKER"), eq("MANUAL_CLOSE"),
                    eq("10"), eq("BUDGET_POOL"), eq("OPEN"), eq("CLOSED"), eq("admin"), isNull());
        }

        @Test
        @DisplayName("ManualOpen records correct beforeValue in audit log")
        void testManualOpen_CorrectAuditBeforeValue() {
            CircuitBreaker cb = new CircuitBreaker();
            cb.setId(1L);
            cb.setPoolId(10L);
            cb.setStatus(CircuitBreakerStatus.CLOSED.name());

            when(circuitBreakerMapper.selectByPoolId(10L)).thenReturn(cb);
            when(circuitBreakerMapper.casTransition(1L, "CLOSED", "OPEN")).thenReturn(1);

            circuitBreakerService.manualOpen(10L, "admin");

            verify(auditLogService).log(eq("CIRCUIT_BREAKER"), eq("MANUAL_OPEN"),
                    eq("10"), eq("BUDGET_POOL"), eq("CLOSED"), eq("OPEN"), eq("admin"), isNull());
        }

        @Test
        @DisplayName("CAS transition failure on recovery does not crash")
        void testRecovery_CasFailure_IsolatesException() {
            CircuitBreaker cb = new CircuitBreaker();
            cb.setId(1L);
            cb.setPoolId(10L);
            cb.setStatus(CircuitBreakerStatus.OPEN.name());

            when(circuitBreakerMapper.findOpenBreakersReadyForRecovery()).thenReturn(List.of(cb));
            when(circuitBreakerMapper.casTransition(1L, "OPEN", "HALF_OPEN")).thenReturn(0);
            when(circuitBreakerMapper.selectList(any())).thenReturn(Collections.emptyList());

            // Should not throw
            assertDoesNotThrow(() -> circuitBreakerService.processRecoveryChecks());
        }
    }

    // =====================================================================
    // 8. Risk control auto-freeze
    // =====================================================================
    @Nested
    @DisplayName("Risk Control Auto-Freeze")
    class RiskControlAutoFreezeTest {

        @InjectMocks
        private RiskControlServiceImpl riskControlService;

        @Mock private CircuitBreakerMapper circuitBreakerMapper;
        @Mock private CircuitBreakerService circuitBreakerService;
        @Mock private RiskControlConfigMapper riskControlConfigMapper;
        @Mock private RiskEventMapper riskEventMapper;
        @Mock private BudgetPoolService budgetPoolService;
        @Mock private BlacklistService blacklistService;
        @Mock private RiskFreezeReviewService riskFreezeReviewService;
        @Mock private AuditLogService auditLogService;

        @Spy
        private ObjectMapper objectMapper = new ObjectMapper();

        @BeforeEach
        void setUp() {
            lenient().when(riskFreezeReviewService.createRiskFreeze(anyLong(), anyLong(), anyLong(),
                    anyString(), anyLong(), anyInt())).thenReturn(new RiskFreezeOrder());
        }

        @Test
        @DisplayName("High frequency breach creates freeze order")
        void testHighFrequencyBreach_CreatesFreezeOrder() {
            RiskControlConfig config = new RiskControlConfig();
            config.setPoolId(1L);
            config.setRuleType("HIGH_FREQUENCY");
            config.setThresholdValue("{\"maxClaims\":5,\"windowMinutes\":5}");
            config.setCooldownMinutes(30);
            config.setMaxTestRequests(10);

            when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
            when(riskEventMapper.countMemberClaimsSince(eq(1001L), any(LocalDateTime.class))).thenReturn(10);
            when(riskEventMapper.insert(any(RiskEvent.class))).thenAnswer(invocation -> {
            RiskEvent e = invocation.getArgument(0);
            e.setId(1L);
            return 1;
        });
            when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);
            when(circuitBreakerMapper.insert(any())).thenReturn(1);

            riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 500L);

            // Verify freeze order was created
            verify(riskFreezeReviewService).createRiskFreeze(eq(1001L), eq(1L), anyLong(),
                    eq("HIGH_FREQUENCY"), eq(500L), anyInt());
        }

        @Test
        @DisplayName("No breach does not create freeze order")
        void testNoBreach_NoFreezeOrder() {
            RiskControlConfig config = new RiskControlConfig();
            config.setRuleType("HIGH_FREQUENCY");
            config.setThresholdValue("{\"maxClaims\":100,\"windowMinutes\":5}");
            config.setCooldownMinutes(30);
            config.setMaxTestRequests(10);

            when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
            when(riskEventMapper.countMemberClaimsSince(eq(1001L), any(LocalDateTime.class))).thenReturn(3);

            riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

            verify(riskFreezeReviewService, never()).createRiskFreeze(anyLong(), anyLong(), anyLong(),
                    anyString(), anyLong(), anyInt());
            verify(circuitBreakerMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Breach trips circuit breaker AND creates audit log")
        void testBreach_TripsAndAudits() {
            RiskControlConfig config = new RiskControlConfig();
            config.setPoolId(1L);
            config.setRuleType("BLACKLIST_HIT");
            config.setThresholdValue("{}");
            config.setCooldownMinutes(30);
            config.setMaxTestRequests(10);

            when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
            when(blacklistService.isBlacklisted(1001L)).thenReturn(true);
            when(riskEventMapper.insert(any(RiskEvent.class))).thenAnswer(invocation -> {
            RiskEvent e = invocation.getArgument(0);
            e.setId(1L);
            return 1;
        });

            CircuitBreaker existingCb = new CircuitBreaker();
            existingCb.setId(1L);
            existingCb.setPoolId(1L);
            existingCb.setStatus(CircuitBreakerStatus.CLOSED.name());
            when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(existingCb);
            when(circuitBreakerMapper.tripBreaker(1L)).thenReturn(1);

            riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

            verify(circuitBreakerMapper).tripBreaker(1L);
            verify(auditLogService).log(eq("CIRCUIT_BREAKER"), eq("AUTO_TRIP"), eq("1"),
                    eq("BUDGET_POOL"), eq("CLOSED"), eq("OPEN"), eq("SYSTEM"), isNull());
            verify(auditLogService).log(eq("RISK_CONTROL"), eq("AUTO_FREEZE"), eq("1001"),
                    eq("MEMBER"), anyString(), eq("FROZEN"), eq("SYSTEM"), anyString());
        }
    }
}
