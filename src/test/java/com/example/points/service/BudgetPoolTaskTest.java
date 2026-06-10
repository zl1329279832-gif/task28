package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.entity.BudgetPool;
import com.example.points.enums.BudgetPoolStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.scheduled.BudgetPoolTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetPoolTaskTest {

    @InjectMocks
    private BudgetPoolTask budgetPoolTask;

    @Mock private BudgetPoolMapper budgetPoolMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
    }

    @Test
    void testCheckAndCircuitBreak_TriggersAtThreshold() {
        BudgetPool pool = BudgetPool.builder()
                .id(1L).activityCode("DOUBLE11")
                .totalBudget(100000L).usedBudget(95000L)
                .circuitBreakRate(90).status(1).build();

        when(budgetPoolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(pool));

        budgetPoolTask.checkAndCircuitBreak();

        verify(budgetPoolMapper).updateStatus(1L, BudgetPoolStatus.CIRCUIT_BROKEN.getCode());
        verify(auditLogService).log(eq("BUDGET_POOL"), eq("CIRCUIT_BREAK"),
                eq("1"), any(), any(), any(), any(), any());
    }

    @Test
    void testCheckAndCircuitBreak_BelowThreshold_NoAction() {
        BudgetPool pool = BudgetPool.builder()
                .id(1L).activityCode("DOUBLE11")
                .totalBudget(100000L).usedBudget(50000L)
                .circuitBreakRate(90).status(1).build();

        when(budgetPoolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(pool));

        budgetPoolTask.checkAndCircuitBreak();

        verify(budgetPoolMapper, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    void testCheckAndCircuitBreak_LockNotAcquired_Skips() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        budgetPoolTask.checkAndCircuitBreak();

        verify(budgetPoolMapper, never()).selectList(any());
    }

    @Test
    void testReleaseExpiredPools_DisablesExpired() {
        BudgetPool pool = BudgetPool.builder()
                .id(1L).activityCode("EXPIRED_ACT")
                .totalBudget(10000L).usedBudget(5000L)
                .status(1).effectiveEnd(LocalDateTime.now().minusDays(1)).build();

        when(budgetPoolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(pool));

        budgetPoolTask.releaseExpiredPools();

        verify(budgetPoolMapper).updateStatus(1L, BudgetPoolStatus.DISABLED.getCode());
    }

    @Test
    void testCheckAndCircuitBreak_MultiplePoolsProcessed() {
        BudgetPool pool1 = BudgetPool.builder()
                .id(1L).totalBudget(100000L).usedBudget(95000L)
                .circuitBreakRate(90).status(1).build();
        BudgetPool pool2 = BudgetPool.builder()
                .id(2L).totalBudget(50000L).usedBudget(10000L)
                .circuitBreakRate(90).status(1).build();
        BudgetPool pool3 = BudgetPool.builder()
                .id(3L).totalBudget(80000L).usedBudget(75000L)
                .circuitBreakRate(90).status(1).build();

        when(budgetPoolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(pool1, pool2, pool3));

        budgetPoolTask.checkAndCircuitBreak();

        // pool1 (95%) and pool3 (93.75%) should be circuit broken
        verify(budgetPoolMapper).updateStatus(1L, BudgetPoolStatus.CIRCUIT_BROKEN.getCode());
        verify(budgetPoolMapper).updateStatus(3L, BudgetPoolStatus.CIRCUIT_BROKEN.getCode());
        // pool2 (20%) should NOT be circuit broken
        verify(budgetPoolMapper, never()).updateStatus(eq(2L), anyInt());
    }

    @Test
    void testCheckAndCircuitBreak_SinglePoolFailure_ContinuesOthers() {
        BudgetPool pool1 = BudgetPool.builder()
                .id(1L).totalBudget(100000L).usedBudget(95000L)
                .circuitBreakRate(90).status(1).build();
        BudgetPool pool2 = BudgetPool.builder()
                .id(2L).totalBudget(100000L).usedBudget(95000L)
                .circuitBreakRate(90).status(1).build();

        when(budgetPoolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(pool1, pool2));

        // First pool update throws exception
        when(budgetPoolMapper.updateStatus(eq(1L), anyInt()))
                .thenThrow(new RuntimeException("DB error"));
        when(budgetPoolMapper.updateStatus(eq(2L), anyInt())).thenReturn(1);

        budgetPoolTask.checkAndCircuitBreak();

        // Second pool should still be processed
        verify(budgetPoolMapper).updateStatus(2L, BudgetPoolStatus.CIRCUIT_BROKEN.getCode());
    }
}
