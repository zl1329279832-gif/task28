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
    @Mock private BudgetPoolService budgetPoolService;
    @Mock private RiskControlService riskControlService;
    @Mock private RLock rLock;
    @Mock private RBucket<Object> rBucket;

    @BeforeEach
    void setUp() throws Exception {
        // Setup Redisson mock
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        lenient().when(redissonClient.getBucket(anyString())).thenReturn(rBucket);
    }

    @Test
    void testProcessEvent_Register_Success() {
        // Given
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

        // Re-read account after addPoints for accurate flow values
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(100L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        // When
        PointsFlow result = pointsEventService.processEvent(request);

        // Then
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

        // Re-read account after addPoints for accurate flow values
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(150L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.adjust(request);

        assertNotNull(result);
        assertEquals(50L, result.getPointsChange());
        assertEquals("ADJUST", result.getEventType());
        assertEquals(100L, result.getBeforePoints());
        assertEquals(150L, result.getAfterPoints());
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

        // Re-read account after deductPoints for accurate flow values
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(70L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFlow result = pointsEventService.adjust(request);

        assertNotNull(result);
        assertEquals(-30L, result.getPointsChange());
        verify(accountMapper).deductPoints(1001L, 30L);
        assertEquals(100L, result.getBeforePoints());
        assertEquals(70L, result.getAfterPoints());
    }

    @Test
    void testRefund_Success() throws Exception {
        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-001");
        request.setEventId("refund-001");
        request.setMemberId(1001L);

        // First idempotent check (pre-lock) returns null
        when(flowService.checkIdempotent("refund-001")).thenReturn(null);

        // Original purchase flow found by bizOrderNo lookup
        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        originalFlow.setBizOrderNo("ORD-001");
        originalFlow.setEventType("PURCHASE");
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        // Account before refund
        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountService.getAccount(1001L)).thenReturn(account);

        // Add refund points
        when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

        // Re-read account after addPoints for accurate flow values
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(700L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        // When
        PointsFlow result = pointsEventService.refund(request);

        // Then
        assertNotNull(result);
        assertEquals(200L, result.getPointsChange());
        assertEquals("REFUND", result.getEventType());
        assertEquals(500L, result.getBeforePoints());
        assertEquals(700L, result.getAfterPoints());
    }

    @Test
    void testProcessEvent_AccurateFlowRecord() {
        // Given
        PointsEventRequest request = new PointsEventRequest();
        request.setEventId("proc-accurate-001");
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("proc-accurate-001")).thenReturn(null);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(0L);
        account.setMonthlyEarned(0L);
        account.setStatus(1);
        when(accountService.getOrCreateAccount(1001L)).thenReturn(account);

        when(ruleEngine.calculatePoints(eq(request), any(PointsAccount.class))).thenReturn(100L);
        when(accountMapper.addPoints(1001L, 100L)).thenReturn(1);

        // Re-read account after addPoints returns 250 (e.g. DB has accumulated value)
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(250L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        // When
        PointsFlow result = pointsEventService.processEvent(request);

        // Then - flow record uses re-read values: beforePoints = afterPoints - points = 250 - 100 = 150
        assertNotNull(result);
        assertEquals(100L, result.getPointsChange());
        assertEquals(150L, result.getBeforePoints());
        assertEquals(250L, result.getAfterPoints());
    }

    @Test
    void testAdjust_LockNotAcquired_ThrowsException() throws Exception {
        // tryLock returns false
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-lock-001");
        assertThrows(BusinessException.class, () -> pointsEventService.adjust(request));
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testAdjust_DuplicateInsideLock_ReturnsExisting() throws Exception {
        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("adj-dup-001");
        existingFlow.setPointsChange(50L);
        // adjust() only checks idempotent inside lock (no pre-lock check)
        when(flowService.checkIdempotent("adj-dup-001")).thenReturn(existingFlow);
        AdjustRequest request = new AdjustRequest();
        request.setMemberId(1001L);
        request.setPoints(50L);
        request.setEventId("adj-dup-001");
        PointsFlow result = pointsEventService.adjust(request);
        assertNotNull(result);
        assertEquals("adj-dup-001", result.getEventId());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testRefund_DuplicateInsideLock_ReturnsExisting() throws Exception {
        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("refund-dup-001");
        // First checkIdempotent returns null (pre-lock), second (inside lock) returns existing
        when(flowService.checkIdempotent("refund-dup-001"))
                .thenReturn(null)
                .thenReturn(existingFlow);

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-001");
        request.setEventId("refund-dup-001");
        request.setMemberId(1001L);

        PointsFlow result = pointsEventService.refund(request);
        assertNotNull(result);
        assertEquals("refund-dup-001", result.getEventId());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testRefund_OriginalFlowNotFound_ThrowsException() {
        when(flowService.checkIdempotent("refund-no-orig")).thenReturn(null);
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-NONE");
        request.setEventId("refund-no-orig");
        request.setMemberId(1001L);

        assertThrows(BusinessException.class, () -> pointsEventService.refund(request));
    }

    @Test
    void testRefund_PartialRefund() throws Exception {
        when(flowService.checkIdempotent("refund-partial")).thenReturn(null);

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(600L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-001");
        request.setEventId("refund-partial");
        request.setMemberId(1001L);
        request.setRefundAmount(100L);

        PointsFlow result = pointsEventService.refund(request);
        assertNotNull(result);
        assertEquals(100L, result.getPointsChange());
        verify(accountMapper).addPoints(1001L, 100L);
    }

    @Test
    void testRefund_PartialRefund_CappedAtOriginal() throws Exception {
        when(flowService.checkIdempotent("refund-capped")).thenReturn(null);

        PointsFlow originalFlow = new PointsFlow();
        originalFlow.setPointsChange(200L);
        when(flowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(originalFlow);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(accountMapper.addPoints(1001L, 200L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(700L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        RefundRequest request = new RefundRequest();
        request.setBizOrderNo("ORD-001");
        request.setEventId("refund-capped");
        request.setMemberId(1001L);
        request.setRefundAmount(300L); // More than original 200

        PointsFlow result = pointsEventService.refund(request);
        assertNotNull(result);
        assertEquals(200L, result.getPointsChange()); // Capped at original
        verify(accountMapper).addPoints(1001L, 200L);
    }
}
