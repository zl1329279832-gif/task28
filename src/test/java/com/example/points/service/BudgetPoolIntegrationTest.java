package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.PointsEventRequest;
import com.example.points.dto.RedeemRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.*;
import com.example.points.mapper.*;
import com.example.points.service.impl.BenefitServiceImpl;
import com.example.points.service.impl.PointsEventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetPoolIntegrationTest {

    @Nested
    @ExtendWith(MockitoExtension.class)
    class PointsEventWithBudgetPool {

        @InjectMocks private PointsEventServiceImpl pointsEventService;

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
        @Mock private RLock rLock;
        @Mock private RBucket<Object> rBucket;

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
            lenient().when(redissonClient.getBucket(anyString())).thenReturn(rBucket);
        }

        @Test
        void testProcessEvent_WithBudgetPool_OccupiesAndEarns() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("bp-001");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setActivityCode("DOUBLE11");

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(flowService.checkIdempotent("bp-001")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(200L);

            BudgetPool pool = BudgetPool.builder().id(1L).activityCode("DOUBLE11").build();
            when(budgetPoolService.getActivePool("DOUBLE11")).thenReturn(pool);
            when(riskControlService.checkBeforeEarn(1001L, 200L, 1L)).thenReturn(null);
            when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setAvailablePoints(200L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            PointsFlow result = pointsEventService.processEvent(request);

            assertNotNull(result);
            assertEquals(200L, result.getPointsChange());
            assertEquals(1L, result.getPoolId());
            verify(budgetPoolService).occupyBudget(1L, 1001L, 200L, "bp-001", null);
        }

        @Test
        void testProcessEvent_BudgetExhausted_ThrowsException() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("bp-002");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setActivityCode("DOUBLE11");

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(flowService.checkIdempotent("bp-002")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(200L);

            BudgetPool pool = BudgetPool.builder().id(1L).activityCode("DOUBLE11").build();
            when(budgetPoolService.getActivePool("DOUBLE11")).thenReturn(pool);
            when(riskControlService.checkBeforeEarn(1001L, 200L, 1L)).thenReturn(null);
            doThrow(new BusinessException("预算池额度不足"))
                    .when(budgetPoolService).occupyBudget(1L, 1001L, 200L, "bp-002", null);

            assertThrows(BusinessException.class, () -> pointsEventService.processEvent(request));
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        void testProcessEvent_NoBudgetPool_NormalFlow() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("bp-003");
            request.setEventType("REGISTER");
            request.setMemberId(1001L);
            // No activityCode set

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(flowService.checkIdempotent("bp-003")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(100L);
            when(budgetPoolService.getActivePool(null)).thenReturn(null);
            when(accountMapper.addPoints(1001L, 100L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setAvailablePoints(100L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            PointsFlow result = pointsEventService.processEvent(request);

            assertNotNull(result);
            assertNull(result.getPoolId());
            verify(budgetPoolService, never()).occupyBudget(anyLong(), anyLong(), anyLong(), any(), any());
        }

        @Test
        void testProcessEvent_RiskDetected_ThrowsException() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("bp-004");
            request.setEventType("ACTIVITY");
            request.setMemberId(1001L);
            request.setActivityCode("DOUBLE11");

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(flowService.checkIdempotent("bp-004")).thenReturn(null);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(0L);
            account.setMonthlyEarned(0L);
            when(accountService.getOrCreateAccount(1001L)).thenReturn(account);
            when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(200L);

            BudgetPool pool = BudgetPool.builder().id(1L).activityCode("DOUBLE11").build();
            when(budgetPoolService.getActivePool("DOUBLE11")).thenReturn(pool);

            RiskEvent riskEvent = RiskEvent.builder().eventNo("RISK_1001").build();
            when(riskControlService.checkBeforeEarn(1001L, 200L, 1L)).thenReturn(riskEvent);

            assertThrows(BusinessException.class, () -> pointsEventService.processEvent(request));
            verify(budgetPoolService, never()).occupyBudget(anyLong(), anyLong(), anyLong(), any(), any());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        void testRefund_WithBudgetPool_ReleasesBack() {
            RefundRequest request = new RefundRequest();
            request.setBizOrderNo("ORD-001");
            request.setEventId("refund-bp-001");
            request.setMemberId(1001L);

            when(flowService.checkIdempotent("refund-bp-001")).thenReturn(null);
            when(riskControlService.checkBeforeRefund(1001L, "ORD-001")).thenReturn(null);

            PointsFlow originalFlow = new PointsFlow();
            originalFlow.setPointsChange(200L);
            originalFlow.setBizOrderNo("ORD-001");
            originalFlow.setEventType("PURCHASE");
            originalFlow.setPoolId(1L);
            when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(500L);
            when(accountService.getAccount(1001L)).thenReturn(account);
            when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setAvailablePoints(700L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            PointsFlow result = pointsEventService.refund(request);

            assertNotNull(result);
            verify(budgetPoolService).releaseBudget(1L, 1001L, 200L, "refund-bp-001", "ORD-001");
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class BenefitWithBudgetPool {

        @InjectMocks private BenefitServiceImpl benefitService;

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
        @Mock private RLock rLock;

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        }

        @Test
        void testRedeem_WithBudgetPool_OccupiesAndRedeems() {
            RedeemRequest request = new RedeemRequest();
            request.setMemberId(1001L);
            request.setBenefitId(1L);
            request.setEventId("redeem-bp-001");
            request.setActivityCode("DOUBLE11");

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
            when(flowService.checkIdempotent("redeem-bp-001")).thenReturn(null);

            Benefit benefit = Benefit.builder()
                    .id(1L).benefitName("优惠券").pointsCost(500L)
                    .availableStock(10).status(1).dailyLimit(0).totalLimit(0).build();
            when(benefitMapper.selectById(1L)).thenReturn(benefit);

            PointsAccount account = new PointsAccount();
            account.setMemberId(1001L);
            account.setAvailablePoints(1000L);
            when(accountService.getAccount(1001L)).thenReturn(account);
            when(accountMapper.deductPoints(1001L, 500L)).thenReturn(1);
            when(benefitMapper.decrementStock(1L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setAvailablePoints(500L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            BudgetPool pool = BudgetPool.builder().id(2L).activityCode("DOUBLE11").build();
            when(budgetPoolService.getActivePool("DOUBLE11")).thenReturn(pool);

            ExchangeRecord result = benefitService.redeem(request);

            assertNotNull(result);
            assertEquals(2L, result.getPoolId());
            verify(budgetPoolService).occupyBudget(eq(2L), eq(1001L), eq(500L),
                    eq("redeem-bp-001"), any());
        }

        @Test
        void testRefundExchange_WithBudgetPool_ReleasesBack() {
            PointsFlow existingFlow = null;
            when(flowService.checkIdempotent("refund-ex-bp")).thenReturn(existingFlow);

            ExchangeRecord record = ExchangeRecord.builder()
                    .id(1L).memberId(1001L).benefitId(1L).exchangeNo("EX-001")
                    .pointsCost(500L).status(1).bizOrderNo("ORD-001").poolId(2L).build();
            when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

            when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);
            when(benefitMapper.incrementStock(1L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setAvailablePoints(1500L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            benefitService.refundExchange("ORD-001", "refund-ex-bp", "admin");

            verify(budgetPoolService).releaseBudget(2L, 1001L, 500L, "refund-ex-bp", "ORD-001");
        }

        @Test
        void testRefundExchange_NoBudgetPool_SkipsRelease() {
            when(flowService.checkIdempotent("refund-ex-no-bp")).thenReturn(null);

            ExchangeRecord record = ExchangeRecord.builder()
                    .id(1L).memberId(1001L).benefitId(1L).exchangeNo("EX-002")
                    .pointsCost(500L).status(1).bizOrderNo("ORD-002").poolId(null).build();
            when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

            when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);
            when(benefitMapper.incrementStock(1L)).thenReturn(1);

            PointsAccount updatedAccount = new PointsAccount();
            updatedAccount.setAvailablePoints(1500L);
            when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

            benefitService.refundExchange("ORD-002", "refund-ex-no-bp", "admin");

            verify(budgetPoolService, never()).releaseBudget(anyLong(), anyLong(),
                    anyLong(), any(), any());
        }
    }
}
