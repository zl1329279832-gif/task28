package com.example.points.service;

import com.example.points.entity.CircuitBreaker;
import com.example.points.enums.CircuitBreakerStatus;
import com.example.points.mapper.CircuitBreakerMapper;
import com.example.points.service.impl.CircuitBreakerServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerServiceTest {

    @InjectMocks
    private CircuitBreakerServiceImpl circuitBreakerService;

    @Mock
    private CircuitBreakerMapper circuitBreakerMapper;

    @Mock
    private AuditLogService auditLogService;

    @Test
    void testProcessRecoveryChecks_OpenToHalfOpen() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setId(1L);
        cb.setPoolId(10L);
        cb.setStatus(CircuitBreakerStatus.OPEN.name());

        when(circuitBreakerMapper.findOpenBreakersReadyForRecovery()).thenReturn(List.of(cb));
        when(circuitBreakerMapper.casTransition(1L, "OPEN", "HALF_OPEN")).thenReturn(1);
        when(circuitBreakerMapper.selectList(any())).thenReturn(Collections.emptyList());

        circuitBreakerService.processRecoveryChecks();

        verify(circuitBreakerMapper).casTransition(1L, "OPEN", "HALF_OPEN");
    }

    @Test
    void testProcessRecoveryChecks_OpenNotReady_Skips() {
        when(circuitBreakerMapper.findOpenBreakersReadyForRecovery()).thenReturn(Collections.emptyList());
        when(circuitBreakerMapper.selectList(any())).thenReturn(Collections.emptyList());

        circuitBreakerService.processRecoveryChecks();

        verify(circuitBreakerMapper, never()).casTransition(anyLong(), anyString(), anyString());
    }

    @Test
    void testProcessRecoveryChecks_HalfOpenToClosed() {
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

        verify(circuitBreakerMapper).closeFromHalfOpen(10L);
    }

    @Test
    void testManualClose() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setId(1L);
        cb.setPoolId(10L);
        cb.setStatus(CircuitBreakerStatus.OPEN.name());

        when(circuitBreakerMapper.selectByPoolId(10L)).thenReturn(cb);
        when(circuitBreakerMapper.casTransition(1L, "OPEN", "CLOSED")).thenReturn(1);
        when(circuitBreakerMapper.updateById(any())).thenReturn(1);

        circuitBreakerService.manualClose(10L, "admin");

        verify(auditLogService).log(eq("CIRCUIT_BREAKER"), eq("MANUAL_CLOSE"),
                eq("10"), eq("BUDGET_POOL"), anyString(), eq("CLOSED"), eq("admin"), isNull());
    }

    @Test
    void testManualOpen() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setId(1L);
        cb.setPoolId(10L);
        cb.setStatus(CircuitBreakerStatus.CLOSED.name());

        when(circuitBreakerMapper.selectByPoolId(10L)).thenReturn(cb);
        when(circuitBreakerMapper.casTransition(1L, "CLOSED", "OPEN")).thenReturn(1);

        circuitBreakerService.manualOpen(10L, "admin");

        verify(auditLogService).log(eq("CIRCUIT_BREAKER"), eq("MANUAL_OPEN"),
                eq("10"), eq("BUDGET_POOL"), anyString(), eq("OPEN"), eq("admin"), isNull());
    }

    @Test
    void testRecordHalfOpenSuccess() {
        when(circuitBreakerMapper.incrementHalfOpenCount(10L)).thenReturn(1);
        circuitBreakerService.recordHalfOpenSuccess(10L);
        verify(circuitBreakerMapper).incrementHalfOpenCount(10L);
    }

    @Test
    void testRecordHalfOpenFailure_Reopens() {
        when(circuitBreakerMapper.reopenFromHalfOpen(10L)).thenReturn(1);
        circuitBreakerService.recordHalfOpenFailure(10L);
        verify(circuitBreakerMapper).reopenFromHalfOpen(10L);
    }

    @Test
    void testProcessRecoveryChecks_HalfOpenToClosed_BudgetAlreadyReleased_NoReRelease() {
        CircuitBreaker cb = new CircuitBreaker();
        cb.setId(1L);
        cb.setPoolId(10L);
        cb.setStatus(CircuitBreakerStatus.HALF_OPEN.name());
        cb.setHalfOpenCount(10);
        cb.setMaxTestRequests(10);
        cb.setBudgetReleased(1);

        when(circuitBreakerMapper.findOpenBreakersReadyForRecovery()).thenReturn(Collections.emptyList());
        when(circuitBreakerMapper.selectList(any())).thenReturn(List.of(cb));
        when(circuitBreakerMapper.closeFromHalfOpenResetBudget(10L)).thenReturn(1);

        circuitBreakerService.processRecoveryChecks();

        verify(circuitBreakerMapper).closeFromHalfOpenResetBudget(10L);
        verify(circuitBreakerMapper, never()).closeFromHalfOpen(anyLong());
    }

    @Test
    void testRecordTrip_WithBudgetReleased() {
        when(circuitBreakerMapper.tripBreakerWithBudgetFlag(10L, 1)).thenReturn(1);
        circuitBreakerService.recordTrip(10L, true);
        verify(circuitBreakerMapper).tripBreakerWithBudgetFlag(10L, 1);
    }

    @Test
    void testRecordTrip_WithoutBudgetReleased() {
        when(circuitBreakerMapper.tripBreakerWithBudgetFlag(10L, 0)).thenReturn(1);
        circuitBreakerService.recordTrip(10L, false);
        verify(circuitBreakerMapper).tripBreakerWithBudgetFlag(10L, 0);
    }
}
