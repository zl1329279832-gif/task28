package com.example.points.service;

import com.example.points.entity.CircuitBreaker;
import com.example.points.entity.RiskControlConfig;
import com.example.points.entity.BudgetPool;
import com.example.points.enums.BudgetPoolStatus;
import com.example.points.enums.CircuitBreakerStatus;
import com.example.points.mapper.CircuitBreakerMapper;
import com.example.points.mapper.RiskControlConfigMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.service.impl.RiskControlServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskControlServiceTest {

    @InjectMocks
    private RiskControlServiceImpl riskControlService;

    @Mock private CircuitBreakerMapper circuitBreakerMapper;
    @Mock private CircuitBreakerService circuitBreakerService;
    @Mock private RiskControlConfigMapper riskControlConfigMapper;
    @Mock private RiskEventMapper riskEventMapper;
    @Mock private BudgetPoolService budgetPoolService;
    @Mock private BlacklistService blacklistService;
    @Mock private PointsFreezeService pointsFreezeService;
    @Mock private AuditLogService auditLogService;
    @Mock private org.redisson.api.RedissonClient redissonClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    // --- isCircuitBreakerAllowing tests ---

    @Test
    void testIsCircuitBreakerAllowing_Closed_ReturnsTrue() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setStatus(CircuitBreakerStatus.CLOSED.name());
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(cb);

        assertTrue(riskControlService.isCircuitBreakerAllowing(1L));
    }

    @Test
    void testIsCircuitBreakerAllowing_Open_ReturnsFalse() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setStatus(CircuitBreakerStatus.OPEN.name());
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(cb);

        assertFalse(riskControlService.isCircuitBreakerAllowing(1L));
    }

    @Test
    void testIsCircuitBreakerAllowing_HalfOpen_UnderLimit_ReturnsTrue() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setStatus(CircuitBreakerStatus.HALF_OPEN.name());
        cb.setHalfOpenCount(3);
        cb.setMaxTestRequests(10);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(cb);

        assertTrue(riskControlService.isCircuitBreakerAllowing(1L));
    }

    @Test
    void testIsCircuitBreakerAllowing_HalfOpen_AtLimit_ReturnsFalse() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setStatus(CircuitBreakerStatus.HALF_OPEN.name());
        cb.setHalfOpenCount(10);
        cb.setMaxTestRequests(10);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(cb);

        assertFalse(riskControlService.isCircuitBreakerAllowing(1L));
    }

    @Test
    void testIsCircuitBreakerAllowing_NoBreaker_ReturnsTrue() {
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);

        assertTrue(riskControlService.isCircuitBreakerAllowing(1L));
    }

    @Test
    void testIsCircuitBreakerAllowing_NullPoolId_ReturnsTrue() {
        assertTrue(riskControlService.isCircuitBreakerAllowing(null));
    }

    // --- evaluatePostIssuance tests ---

    @Test
    void testEvaluatePostIssuance_NullPoolId_Skips() {
        riskControlService.evaluatePostIssuance(1001L, null, 1L, 100L);
        verifyNoInteractions(riskControlConfigMapper);
    }

    @Test
    void testEvaluatePostIssuance_NoBreaches() {
        RiskControlConfig config = new RiskControlConfig();
        config.setRuleType("HIGH_FREQUENCY");
        config.setThresholdValue("{\"maxClaims\":10,\"windowMinutes\":5}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);
        config.setEnabled(1);

        org.redisson.api.RAtomicLong atomicLong = mock(org.redisson.api.RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        when(atomicLong.incrementAndGet()).thenReturn(3L);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        // No circuit breaker should be tripped
        verify(circuitBreakerMapper, never()).insert(any());
        verify(circuitBreakerMapper, never()).tripBreakerWithBudgetFlag(anyLong(), anyInt());
    }

    @Test
    void testEvaluateHighFrequency_Breached() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("HIGH_FREQUENCY");
        config.setThresholdValue("{\"maxClaims\":5,\"windowMinutes\":5}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);
        config.setEnabled(1);

        org.redisson.api.RAtomicLong atomicLong = mock(org.redisson.api.RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);
        when(atomicLong.incrementAndGet()).thenReturn(10L);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(circuitBreakerMapper.insert(any())).thenReturn(1);

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(riskEventMapper).insertIgnoreDuplicate(any());
        verify(circuitBreakerMapper).insert(any());
    }

    @Test
    void testEvaluateBlacklistHit_Breached() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("BLACKLIST_HIT");
        config.setThresholdValue("{}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);
        config.setEnabled(1);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(circuitBreakerMapper.insert(any())).thenReturn(1);

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(riskEventMapper).insertIgnoreDuplicate(any());
    }

    @Test
    void testEvaluatePostIssuance_BlacklistHit_TriggersFreeze() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("BLACKLIST_HIT");
        config.setThresholdValue("{}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);
        config.setEnabled(1);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(circuitBreakerMapper.insert(any())).thenReturn(1);

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(pointsFreezeService).freezeWithBudget(any(), eq(1L));
        verify(auditLogService).log(eq("RISK_CONTROL"), eq("AUTO_FREEZE"), eq("100"),
                eq("POINTS_FLOW"), isNull(), eq("50"), eq("SYSTEM"), isNull());
    }

    @Test
    void testEvaluateBudgetExhaustion_Breached() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("BUDGET_EXHAUSTION");
        config.setThresholdValue("{\"usageThresholdPercent\":80}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);
        config.setEnabled(1);

        BudgetPool pool = new BudgetPool();
        pool.setId(1L);
        pool.setTotalBudget(10000L);
        pool.setUsedBudget(9000L);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(budgetPoolService.getPool(1L)).thenReturn(pool);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(circuitBreakerMapper.insert(any())).thenReturn(1);

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(riskEventMapper).insertIgnoreDuplicate(any());
    }

    @Test
    void testEvaluateAbnormalRefund_Breached() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("ABNORMAL_REFUND");
        config.setThresholdValue("{\"maxRefundRatePercent\":30,\"windowHours\":24}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);
        config.setEnabled(1);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(riskEventMapper.countMemberRefundsSince(eq(1001L), any(LocalDateTime.class))).thenReturn(5);
        when(riskEventMapper.countMemberIssuancesSince(eq(1001L), any(LocalDateTime.class))).thenReturn(10);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(circuitBreakerMapper.insert(any())).thenReturn(1);

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(riskEventMapper).insertIgnoreDuplicate(any());
    }

    @Test
    void testEvaluatePostIssuance_TripsExistingBreaker() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("BLACKLIST_HIT");
        config.setThresholdValue("{}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);

        CircuitBreaker existingCb = new CircuitBreaker();
        existingCb.setId(1L);
        existingCb.setPoolId(1L);
        existingCb.setStatus(CircuitBreakerStatus.CLOSED.name());

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(existingCb);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(circuitBreakerMapper.tripBreakerWithBudgetFlag(1L, 0)).thenReturn(1);

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(circuitBreakerMapper).tripBreakerWithBudgetFlag(1L, 0);
    }

    @Test
    void testEvaluatePostIssuance_NoConfigs_NoAction() {
        when(riskControlConfigMapper.selectList(any())).thenReturn(Collections.emptyList());

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(circuitBreakerMapper, never()).insert(any());
        verify(circuitBreakerMapper, never()).tripBreakerWithBudgetFlag(anyLong(), anyInt());
    }

    @Test
    void testCreateRiskEvent_DuplicateSuppressed() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("BLACKLIST_HIT");
        config.setThresholdValue("{}");
        config.setCooldownMinutes(30);
        config.setMaxTestRequests(10);
        config.setEnabled(1);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);
        when(circuitBreakerMapper.selectByPoolId(1L)).thenReturn(null);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(circuitBreakerMapper.insert(any())).thenReturn(1);

        riskControlService.evaluatePostIssuance(1001L, 1L, 100L, 50L);

        verify(riskEventMapper).insertIgnoreDuplicate(any());
    }

    @Test
    void testEvaluateInTransaction_ReturnsBreaches() {
        RiskControlConfig config = new RiskControlConfig();
        config.setPoolId(1L);
        config.setRuleType("BLACKLIST_HIT");
        config.setThresholdValue("{}");
        config.setEnabled(1);

        when(riskControlConfigMapper.selectList(any())).thenReturn(List.of(config));
        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);
        when(riskEventMapper.insertIgnoreDuplicate(any())).thenReturn(1);

        List<String> result = riskControlService.evaluateInTransaction(1001L, 1L, 100L, 50L);

        assertTrue(result.contains("BLACKLIST_HIT"));
    }

    @Test
    void testEvaluateInTransaction_NullPoolId_ReturnsEmpty() {
        List<String> result = riskControlService.evaluateInTransaction(1001L, null, 100L, 50L);
        assertTrue(result.isEmpty());
    }
}
