package com.example.points.service;

import com.example.points.common.BusinessException;
import com.example.points.entity.BudgetPool;
import com.example.points.enums.BudgetPoolStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.service.impl.BudgetPoolServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetPoolServiceTest {

    @InjectMocks
    private BudgetPoolServiceImpl budgetPoolService;

    @Mock
    private BudgetPoolMapper budgetPoolMapper;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testCreatePool_Success() {
        BudgetPool pool = new BudgetPool();
        pool.setPoolName("活动预算池");
        pool.setTotalBudget(100000L);
        pool.setDailyCap(10000L);
        pool.setMonthlyCap(50000L);
        pool.setStartTime(LocalDateTime.now());
        pool.setEndTime(LocalDateTime.now().plusDays(30));

        when(budgetPoolMapper.insert(any(BudgetPool.class))).thenAnswer(invocation -> {
            BudgetPool p = invocation.getArgument(0);
            p.setId(1L);
            return 1;
        });

        BudgetPool result = budgetPoolService.createPool(pool);

        assertEquals(1L, result.getId());
        assertEquals(BudgetPoolStatus.ACTIVE.getCode(), result.getStatus());
        assertEquals(0L, result.getUsedBudget());
        verify(auditLogService).log(eq("BUDGET_POOL"), eq("CREATE"), anyString(),
                eq("BUDGET_POOL"), isNull(), anyString(), eq("SYSTEM"), isNull());
    }

    @Test
    void testGetPool_Success() {
        BudgetPool pool = new BudgetPool();
        pool.setId(1L);
        pool.setPoolName("Test");
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);

        BudgetPool result = budgetPoolService.getPool(1L);
        assertEquals(1L, result.getId());
    }

    @Test
    void testGetPool_NotFound_Throws() {
        when(budgetPoolMapper.selectById(999L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> budgetPoolService.getPool(999L));
    }

    @Test
    void testReserveBudget_Success() {
        when(budgetPoolMapper.reserveBudget(1L, 500L)).thenReturn(1);
        assertDoesNotThrow(() -> budgetPoolService.reserveBudget(1L, 500L));
    }

    @Test
    void testReserveBudget_InsufficientBudget_Throws() {
        when(budgetPoolMapper.reserveBudget(1L, 500L)).thenReturn(0);
        assertThrows(BusinessException.class, () -> budgetPoolService.reserveBudget(1L, 500L));
    }

    @Test
    void testReserveBudget_DailyCapExceeded_Throws() {
        when(budgetPoolMapper.reserveBudget(1L, 5000L)).thenReturn(0);
        assertThrows(BusinessException.class, () -> budgetPoolService.reserveBudget(1L, 5000L));
    }

    @Test
    void testReserveBudget_MonthlyCapExceeded_Throws() {
        when(budgetPoolMapper.reserveBudget(1L, 20000L)).thenReturn(0);
        assertThrows(BusinessException.class, () -> budgetPoolService.reserveBudget(1L, 20000L));
    }

    @Test
    void testReleaseBudget_Success() {
        when(budgetPoolMapper.releaseBudget(1L, 500L)).thenReturn(1);
        assertDoesNotThrow(() -> budgetPoolService.releaseBudget(1L, 500L));
    }

    @Test
    void testConsumeBudget_Success() {
        when(budgetPoolMapper.consumeBudget(1L, 500L)).thenReturn(1);
        assertDoesNotThrow(() -> budgetPoolService.consumeBudget(1L, 500L));
    }

    @Test
    void testConsumeBudget_Insufficient_Throws() {
        when(budgetPoolMapper.consumeBudget(1L, 500L)).thenReturn(0);
        assertThrows(BusinessException.class, () -> budgetPoolService.consumeBudget(1L, 500L));
    }

    @Test
    void testRestoreBudget_Success() {
        when(budgetPoolMapper.restoreBudget(1L, 500L)).thenReturn(1);
        assertDoesNotThrow(() -> budgetPoolService.restoreBudget(1L, 500L));
    }

    @Test
    void testIsPoolValidFor_ActivePoolMatchingLevel() {
        BudgetPool pool = new BudgetPool();
        pool.setStatus(BudgetPoolStatus.ACTIVE.getCode());
        pool.setStartTime(LocalDateTime.now().minusDays(1));
        pool.setEndTime(LocalDateTime.now().plusDays(30));
        pool.setApplicableLevels("[1,2,3]");

        assertTrue(budgetPoolService.isPoolValidFor(pool, 2L));
    }

    @Test
    void testIsPoolValidFor_ExpiredPool_ReturnsFalse() {
        BudgetPool pool = new BudgetPool();
        pool.setStatus(BudgetPoolStatus.ACTIVE.getCode());
        pool.setStartTime(LocalDateTime.now().minusDays(30));
        pool.setEndTime(LocalDateTime.now().minusDays(1));

        assertFalse(budgetPoolService.isPoolValidFor(pool, 1L));
    }

    @Test
    void testIsPoolValidFor_LevelMismatch_ReturnsFalse() {
        BudgetPool pool = new BudgetPool();
        pool.setStatus(BudgetPoolStatus.ACTIVE.getCode());
        pool.setStartTime(LocalDateTime.now().minusDays(1));
        pool.setEndTime(LocalDateTime.now().plusDays(30));
        pool.setApplicableLevels("[3,4,5]");

        assertFalse(budgetPoolService.isPoolValidFor(pool, 1L));
    }

    @Test
    void testIsPoolValidFor_SuspendedPool_ReturnsFalse() {
        BudgetPool pool = new BudgetPool();
        pool.setStatus(BudgetPoolStatus.SUSPENDED.getCode());

        assertFalse(budgetPoolService.isPoolValidFor(pool, 1L));
    }

    @Test
    void testSuspendPool_AuditLogged() {
        BudgetPool pool = new BudgetPool();
        pool.setId(1L);
        pool.setStatus(BudgetPoolStatus.ACTIVE.getCode());
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(budgetPoolMapper.updateById(any())).thenReturn(1);

        budgetPoolService.suspendPool(1L, "admin");

        assertEquals(BudgetPoolStatus.SUSPENDED.getCode(), pool.getStatus());
        verify(auditLogService).log(eq("BUDGET_POOL"), eq("SUSPEND"), eq("1"),
                eq("BUDGET_POOL"), eq("ACTIVE"), eq("SUSPENDED"), eq("admin"), isNull());
    }

    @Test
    void testReactivatePool_AuditLogged() {
        BudgetPool pool = new BudgetPool();
        pool.setId(1L);
        pool.setStatus(BudgetPoolStatus.SUSPENDED.getCode());
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(budgetPoolMapper.updateById(any())).thenReturn(1);

        budgetPoolService.reactivatePool(1L, "admin");

        assertEquals(BudgetPoolStatus.ACTIVE.getCode(), pool.getStatus());
        verify(auditLogService).log(eq("BUDGET_POOL"), eq("REACTIVATE"), eq("1"),
                eq("BUDGET_POOL"), eq("SUSPENDED"), eq("ACTIVE"), eq("admin"), isNull());
    }
}
