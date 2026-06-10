package com.example.points.service;

import com.example.points.entity.BudgetPool;
import com.example.points.entity.ReviewOrder;
import com.example.points.entity.RiskEvent;
import com.example.points.enums.RiskType;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.mapper.ReviewOrderMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.service.impl.RiskControlServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskControlServiceTest {

    @InjectMocks
    private RiskControlServiceImpl riskControlService;

    @Mock private RiskEventMapper riskEventMapper;
    @Mock private ReviewOrderMapper reviewOrderMapper;
    @Mock private PointsFlowMapper flowMapper;
    @Mock private BudgetPoolMapper budgetPoolMapper;
    @Mock private PointsFreezeService freezeService;
    @Mock private AuditLogService auditLogService;

    @Test
    void testCheckBeforeEarn_NoRisk_ReturnsNull() {
        when(flowMapper.countRecentEarn(eq(1001L), any(LocalDateTime.class))).thenReturn(3);

        RiskEvent result = riskControlService.checkBeforeEarn(1001L, 100L, null);

        assertNull(result);
    }

    @Test
    void testCheckBeforeEarn_HighFrequency_TriggersRisk() {
        when(flowMapper.countRecentEarn(eq(1001L), any(LocalDateTime.class))).thenReturn(15);
        when(riskEventMapper.selectByEventNo(anyString())).thenReturn(null);

        RiskEvent result = riskControlService.checkBeforeEarn(1001L, 100L, null);

        assertNotNull(result);
        assertEquals(RiskType.HIGH_FREQUENCY.getCode(), result.getRiskType());
        verify(riskEventMapper).insert(any(RiskEvent.class));
        verify(reviewOrderMapper).insert(any(ReviewOrder.class));
    }

    @Test
    void testCheckBeforeEarn_ExceedsRiskThreshold_TriggersRisk() {
        when(flowMapper.countRecentEarn(eq(1001L), any(LocalDateTime.class))).thenReturn(2);

        BudgetPool pool = BudgetPool.builder()
                .id(1L).riskThreshold(500L).build();
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(riskEventMapper.selectByEventNo(anyString())).thenReturn(null);

        RiskEvent result = riskControlService.checkBeforeEarn(1001L, 1000L, 1L);

        assertNotNull(result);
        verify(riskEventMapper).insert(any(RiskEvent.class));
    }

    @Test
    void testCheckBeforeRefund_NormalRefund_ReturnsNull() {
        when(flowMapper.countRecentRefund(eq(1001L), any(LocalDateTime.class))).thenReturn(2);

        RiskEvent result = riskControlService.checkBeforeRefund(1001L, "ORD-001");

        assertNull(result);
    }

    @Test
    void testCheckBeforeRefund_AbnormalRefund_TriggersRisk() {
        when(flowMapper.countRecentRefund(eq(1001L), any(LocalDateTime.class))).thenReturn(6);
        when(riskEventMapper.selectByEventNo(anyString())).thenReturn(null);

        RiskEvent result = riskControlService.checkBeforeRefund(1001L, "ORD-001");

        assertNotNull(result);
        assertEquals(RiskType.ABNORMAL_REFUND.getCode(), result.getRiskType());
    }

    @Test
    void testDetectHighFrequency_BelowLimit_ReturnsFalse() {
        when(flowMapper.countRecentEarn(eq(1001L), any(LocalDateTime.class))).thenReturn(5);

        assertFalse(riskControlService.detectHighFrequency(1001L, 5, 10));
    }

    @Test
    void testDetectHighFrequency_AtLimit_ReturnsTrue() {
        when(flowMapper.countRecentEarn(eq(1001L), any(LocalDateTime.class))).thenReturn(10);

        assertTrue(riskControlService.detectHighFrequency(1001L, 5, 10));
    }

    @Test
    void testDetectAbnormalRefund_BelowLimit_ReturnsFalse() {
        when(flowMapper.countRecentRefund(eq(1001L), any(LocalDateTime.class))).thenReturn(3);

        assertFalse(riskControlService.detectAbnormalRefund(1001L, 24, 5));
    }

    @Test
    void testDetectAbnormalRefund_AtLimit_ReturnsTrue() {
        when(flowMapper.countRecentRefund(eq(1001L), any(LocalDateTime.class))).thenReturn(5);

        assertTrue(riskControlService.detectAbnormalRefund(1001L, 24, 5));
    }

    @Test
    void testTriggerRiskEvent_CreatesEventAndReviewOrder() {
        when(riskEventMapper.selectByEventNo(anyString())).thenReturn(null);

        RiskEvent result = riskControlService.triggerRiskEvent(
                1001L, "HIGH_FREQUENCY", "test detail", "1,2,3", 1L);

        assertNotNull(result);
        assertEquals(1001L, result.getMemberId());
        assertEquals("HIGH_FREQUENCY", result.getRiskType());
        assertEquals(1L, result.getPoolId());

        verify(riskEventMapper).insert(any(RiskEvent.class));
        verify(reviewOrderMapper).insert(any(ReviewOrder.class));
        verify(auditLogService).log(eq("RISK_CONTROL"), eq("TRIGGER"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void testTriggerRiskEvent_Idempotent_SameDaySameType() {
        RiskEvent existing = RiskEvent.builder()
                .id(1L).eventNo("RISK_1001_HIGH_FREQUENCY_2026-06-10")
                .memberId(1001L).riskType("HIGH_FREQUENCY").build();
        when(riskEventMapper.selectByEventNo(anyString())).thenReturn(existing);

        RiskEvent result = riskControlService.triggerRiskEvent(
                1001L, "HIGH_FREQUENCY", "test detail", null, null);

        assertEquals(existing, result);
        verify(riskEventMapper, never()).insert(any());
        verify(reviewOrderMapper, never()).insert(any());
    }

    @Test
    void testTriggerRiskEvent_SetsPoolId() {
        when(riskEventMapper.selectByEventNo(anyString())).thenReturn(null);

        riskControlService.triggerRiskEvent(1001L, "BUDGET_EXHAUSTED", "detail", null, 5L);

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(riskEventMapper).insert(captor.capture());
        assertEquals(5L, captor.getValue().getPoolId());
    }
}
