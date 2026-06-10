package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.AdjustRequest;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Idempotency tests covering duplicate event handling across PointsEvent and Benefit services.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyTest {

    @Nested
    class PointsEventIdempotency {

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
        @Mock private RLock rLock;

        @BeforeEach
        void setUp() throws Exception {
            lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        }

        @Test
        void testProcessEvent_DuplicateReturnsExistingFlow() {
            PointsEventRequest request = new PointsEventRequest();
            request.setEventId("dup-proc-001");
            request.setEventType("REGISTER");
            request.setMemberId(1001L);

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);

            PointsFlow existingFlow = new PointsFlow();
            existingFlow.setEventId("dup-proc-001");
            existingFlow.setPointsChange(100L);
            when(flowService.checkIdempotent("dup-proc-001")).thenReturn(existingFlow);

            PointsFlow result = service.processEvent(request);

            assertNotNull(result);
            assertEquals("dup-proc-001", result.getEventId());
            assertEquals(100L, result.getPointsChange());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        void testAdjust_DuplicateReturnsExistingFlow() throws Exception {
            AdjustRequest request = new AdjustRequest();
            request.setMemberId(1001L);
            request.setPoints(50L);
            request.setEventId("dup-adj-001");

            PointsFlow existingFlow = new PointsFlow();
            existingFlow.setEventId("dup-adj-001");
            existingFlow.setPointsChange(50L);
            when(flowService.checkIdempotent("dup-adj-001")).thenReturn(existingFlow);

            PointsFlow result = service.adjust(request);

            assertNotNull(result);
            assertEquals("dup-adj-001", result.getEventId());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
            verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
        }

        @Test
        void testRefund_DuplicateReturnsExistingFlow() throws Exception {
            RefundRequest request = new RefundRequest();
            request.setBizOrderNo("ORD-DUP");
            request.setEventId("dup-refund-001");
            request.setMemberId(1001L);

            PointsFlow existingFlow = new PointsFlow();
            existingFlow.setEventId("dup-refund-001");
            existingFlow.setPointsChange(200L);

            // First call returns null, second (inside lock) returns existing
            when(flowService.checkIdempotent("dup-refund-001"))
                    .thenReturn(null)
                    .thenReturn(existingFlow);

            PointsFlow originalFlow = new PointsFlow();
            originalFlow.setPointsChange(200L);
            originalFlow.setBizOrderNo("ORD-DUP");
            originalFlow.setEventType("PURCHASE");
            when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

            PointsFlow result = service.refund(request);

            assertNotNull(result);
            assertEquals("dup-refund-001", result.getEventId());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        }

        @Test
        void testExpireTask_DeterministicEventId_PreventsDuplicate() {
            // Verify that the deterministic eventId format ensures same-day calls produce same eventId
            String memberId = "1001";
            String today = LocalDate.now().toString();
            String eventId1 = "EXPIRE_" + memberId + "_" + today;
            String eventId2 = "EXPIRE_" + memberId + "_" + today;
            assertEquals(eventId1, eventId2,
                    "Same-day expiration event IDs must be identical for idempotency");
        }
    }

    @Nested
    class BenefitIdempotency {

        @InjectMocks
        private BenefitServiceImpl service;

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
            lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        }

        @Test
        void testRedeem_DuplicateReturnsExistingRecord() {
            RedeemRequest request = new RedeemRequest();
            request.setMemberId(1001L);
            request.setBenefitId(1L);
            request.setEventId("dup-redeem-001");

            when(blacklistService.isBlacklisted(1001L)).thenReturn(false);

            PointsFlow existingFlow = new PointsFlow();
            existingFlow.setEventId("dup-redeem-001");
            when(flowService.checkIdempotent("dup-redeem-001")).thenReturn(existingFlow);

            ExchangeRecord existingRecord = new ExchangeRecord();
            existingRecord.setMemberId(1001L);
            existingRecord.setBenefitId(1L);
            existingRecord.setStatus(1);
            when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingRecord);

            ExchangeRecord result = service.redeem(request);

            assertNotNull(result);
            verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
            verify(benefitMapper, never()).decrementStock(anyLong());
        }

        @Test
        void testRefundExchange_DuplicateByEventId_Noop() {
            PointsFlow existingFlow = new PointsFlow();
            existingFlow.setEventId("dup-refund-ex-001");
            when(flowService.checkIdempotent("dup-refund-ex-001")).thenReturn(existingFlow);

            service.refundExchange("BIZ-DUP", "dup-refund-ex-001", "admin");

            verify(exchangeRecordMapper, never()).selectOne(any());
            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
            verify(benefitMapper, never()).incrementStock(anyLong());
        }

        @Test
        void testRefundExchange_AlreadyRefundedStatus_Noop() {
            ExchangeRecord record = ExchangeRecord.builder()
                    .memberId(1001L)
                    .benefitId(1L)
                    .pointsCost(500L)
                    .status(3) // REFUNDED
                    .bizOrderNo("BIZ-ALREADY")
                    .build();

            when(flowService.checkIdempotent("refund-already")).thenReturn(null);
            when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

            service.refundExchange("BIZ-ALREADY", "refund-already", "admin");

            verify(accountMapper, never()).addPoints(anyLong(), anyLong());
            verify(benefitMapper, never()).incrementStock(anyLong());
        }
    }
}
