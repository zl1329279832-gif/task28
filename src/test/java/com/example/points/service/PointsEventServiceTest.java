package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.AdjustRequest;
import com.example.points.dto.PointsEventRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.entity.PointsRule;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsEventServiceTest {

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
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // Mock TransactionTemplate to execute callbacks directly
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    // ======================== processEvent tests ========================

    @Test
    void testProcessEvent_Register_Success() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("reg-001");
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("reg-001")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);

        when(ruleEngine.calculatePoints(eq(request), any(PointsAccount.class))).thenReturn(100L);
        when(accountMapper.addPoints(1001L, 100L)).thenReturn(1);

        // Mock rule for version tracking
        PointsRule rule = new PointsRule();
        rule.setId(10L);
        rule.setVersion(2);
        when(ruleEngine.getActiveRule("REGISTER")).thenReturn(rule);

        PointsFlow result = pointsEventService.processEvent(request);

        assertNotNull(result);
        assertEquals(100L, result.getPointsChange());
        assertEquals("REGISTER", result.getEventType());
        verify(accountMapper).addPoints(1001L, 100L);
        verify(flowService).saveFlow(any(PointsFlow.class));
    }

    @Test
    void testProcessEvent_Duplicate_ReturnsExisting() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("dup-001");
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);

        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("dup-001");
        existingFlow.setPointsChange(100L);
        when(flowService.checkIdempotent("dup-001")).thenReturn(existingFlow);

        PointsFlow result = pointsEventService.processEvent(request);

        assertNotNull(result);
        assertEquals("dup-001", result.getEventId());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testProcessEvent_Duplicate_InsideLock_ReturnsExisting() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("dup-002");
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        // First call returns null (outside lock), second returns existing (inside lock)
        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("dup-002");
        existingFlow.setPointsChange(100L);
        when(flowService.checkIdempotent("dup-002")).thenReturn(null).thenReturn(existingFlow);

        PointsFlow result = pointsEventService.processEvent(request);

        assertNotNull(result);
        assertEquals("dup-002", result.getEventId());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        // checkIdempotent called twice: once outside lock, once inside
        verify(flowService, times(2)).checkIdempotent("dup-002");
    }

    @Test
    void testProcessEvent_Blacklisted_ThrowsException() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("bl-001");
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);

        assertThrows(BusinessException.class, () -> pointsEventService.processEvent(request));
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testProcessEvent_RuleVersionRecorded() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("rv-001");
        request.setEventType("PURCHASE");
        request.setMemberId(1001L);
        request.setAmount(200L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("rv-001")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);

        when(ruleEngine.calculatePoints(eq(request), any())).thenReturn(200L);
        when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

        PointsRule rule = new PointsRule();
        rule.setId(5L);
        rule.setVersion(3);
        when(ruleEngine.getActiveRule("PURCHASE")).thenReturn(rule);

        PointsFlow result = pointsEventService.processEvent(request);

        assertNotNull(result);
        assertEquals(5L, result.getRuleId());
        assertEquals(3, result.getRuleVersion());
    }

    // ======================== adjust tests ========================

    @Test
    void testAdjust_AddPoints() {
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-001");
        request.setOperator("admin");

        when(flowService.checkIdempotent("adj-001")).thenReturn(null);
        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(100L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 50L)).thenReturn(1);

        PointsFlow result = pointsEventService.adjust(request);

        assertNotNull(result);
        assertEquals(50L, result.getPointsChange());
        assertEquals("ADJUST", result.getEventType());
        // Verify lock was acquired
        verify(redissonClient).getLock("lock:points:event:1001");
    }

    @Test
    void testAdjust_DeductPoints() {
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(-30L);
        request.setEventId("adj-002");
        request.setOperator("admin");

        when(flowService.checkIdempotent("adj-002")).thenReturn(null);
        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(100L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.deductPoints(1001L, 30L)).thenReturn(1);

        PointsFlow result = pointsEventService.adjust(request);

        assertNotNull(result);
        assertEquals(-30L, result.getPointsChange());
        verify(accountMapper).deductPoints(1001L, 30L);
    }

    @Test
    void testAdjust_Duplicate_ReturnsExisting() {
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-dup-001");
        request.setOperator("admin");

        PointsFlow existing = new PointsFlow();
        existing.setEventId("adj-dup-001");
        existing.setPointsChange(50L);
        when(flowService.checkIdempotent("adj-dup-001")).thenReturn(existing);

        PointsFlow result = pointsEventService.adjust(request);

        assertNotNull(result);
        assertEquals("adj-dup-001", result.getEventId());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testAdjust_DoubleCheckIdempotent_InsideLock() {
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-dup-002");
        request.setOperator("admin");

        PointsFlow existing = new PointsFlow();
        existing.setEventId("adj-dup-002");
        existing.setPointsChange(50L);
        // First call null (outside lock), second returns existing (inside lock)
        when(flowService.checkIdempotent("adj-dup-002")).thenReturn(null).thenReturn(existing);

        PointsFlow result = pointsEventService.adjust(request);

        assertNotNull(result);
        assertEquals("adj-dup-002", result.getEventId());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        verify(flowService, times(2)).checkIdempotent("adj-dup-002");
    }

    @Test
    void testAdjust_InsufficientPoints_ThrowsException() {
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(-200L);
        request.setEventId("adj-003");
        request.setOperator("admin");

        when(flowService.checkIdempotent("adj-003")).thenReturn(null);
        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(100L);
        when(accountService.getAccount(1001L)).thenReturn(account);

        assertThrows(BusinessException.class, () -> pointsEventService.adjust(request));
    }

    // ======================== refund tests ========================

    @Test
    void testRefund_Success_UsesRefundPoints() {
        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-001");
        request.setEventId("refund-001");
        request.setMemberId(1001L);

        when(flowService.checkIdempotent("refund-001")).thenReturn(null);

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        originalFlow.setBizOrderNo("ORD-001");
        originalFlow.setEventType("PURCHASE");
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        // No previous refunds
        when(flowMapper.sumRefundedPoints("ORD-001", 1001L)).thenReturn(0L);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.refundPoints(1001L, 200L)).thenReturn(1);

        PointsFlow result = pointsEventService.refund(request);

        assertNotNull(result);
        assertEquals(200L, result.getPointsChange());
        assertEquals("REFUND", result.getEventType());
        // Verify refundPoints is used (not addPoints)
        verify(accountMapper).refundPoints(1001L, 200L);
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testRefund_OverRefund_Prevented() {
        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-002");
        request.setEventId("refund-002");
        request.setMemberId(1001L);
        request.setRefundAmount(150L);

        when(flowService.checkIdempotent("refund-002")).thenReturn(null);

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        originalFlow.setBizOrderNo("ORD-002");
        originalFlow.setEventType("PURCHASE");
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        // 100 points already refunded — only 100 remaining
        when(flowMapper.sumRefundedPoints("ORD-002", 1001L)).thenReturn(100L);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.refundPoints(1001L, 100L)).thenReturn(1);

        PointsFlow result = pointsEventService.refund(request);

        assertNotNull(result);
        // Capped at remaining 100, not requested 150
        assertEquals(100L, result.getPointsChange());
        verify(accountMapper).refundPoints(1001L, 100L);
    }

    @Test
    void testRefund_FullyRefunded_ThrowsException() {
        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-003");
        request.setEventId("refund-003");
        request.setMemberId(1001L);

        when(flowService.checkIdempotent("refund-003")).thenReturn(null);

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        originalFlow.setBizOrderNo("ORD-003");
        originalFlow.setEventType("PURCHASE");
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        // Already fully refunded
        when(flowMapper.sumRefundedPoints("ORD-003", 1001L)).thenReturn(200L);

        assertThrows(BusinessException.class, () -> pointsEventService.refund(request));
        verify(accountMapper, never()).refundPoints(anyLong(), anyLong());
    }

    @Test
    void testRefund_Duplicate_ReturnsExisting() {
        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-004");
        request.setEventId("refund-dup-001");
        request.setMemberId(1001L);

        PointsFlow existing = new PointsFlow();
        existing.setEventId("refund-dup-001");
        existing.setPointsChange(200L);
        when(flowService.checkIdempotent("refund-dup-001")).thenReturn(existing);

        PointsFlow result = pointsEventService.refund(request);

        assertNotNull(result);
        assertEquals("refund-dup-001", result.getEventId());
        verify(accountMapper, never()).refundPoints(anyLong(), anyLong());
    }
}
