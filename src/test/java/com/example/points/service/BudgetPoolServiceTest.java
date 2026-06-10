package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.BudgetPoolCreateRequest;
import com.example.points.dto.BudgetPoolUpdateRequest;
import com.example.points.entity.BudgetPool;
import com.example.points.entity.BudgetUsageLog;
import com.example.points.enums.BudgetPoolStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.mapper.BudgetUsageLogMapper;
import com.example.points.service.impl.BudgetPoolServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetPoolServiceTest {

    @InjectMocks
    private BudgetPoolServiceImpl budgetPoolService;

    @Mock private BudgetPoolMapper budgetPoolMapper;
    @Mock private BudgetUsageLogMapper budgetUsageLogMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
    }

    private BudgetPool createTestPool(Long id, String activityCode, Long totalBudget,
                                       Long usedBudget, int status) {
        return BudgetPool.builder()
                .id(id)
                .activityCode(activityCode)
                .activityName("测试活动")
                .totalBudget(totalBudget)
                .usedBudget(usedBudget)
                .frozenBudget(0L)
                .dailyLimit(0L)
                .monthlyLimit(0L)
                .riskThreshold(0L)
                .circuitBreakRate(90)
                .status(status)
                .build();
    }

    @Test
    void testCreatePool_Success() {
        BudgetPoolCreateRequest request = new BudgetPoolCreateRequest();
        request.setActivityCode("DOUBLE11");
        request.setActivityName("双十一活动");
        request.setTotalBudget(100000L);
        request.setOperator("admin");

        when(budgetPoolMapper.selectByActivityCode("DOUBLE11")).thenReturn(null);

        BudgetPool result = budgetPoolService.createPool(request);

        assertNotNull(result);
        assertEquals("DOUBLE11", result.getActivityCode());
        assertEquals(100000L, result.getTotalBudget());
        assertEquals(0L, result.getUsedBudget());
        verify(budgetPoolMapper).insert(any(BudgetPool.class));
    }

    @Test
    void testCreatePool_DuplicateActivityCode_ThrowsException() {
        BudgetPoolCreateRequest request = new BudgetPoolCreateRequest();
        request.setActivityCode("DOUBLE11");
        request.setActivityName("双十一活动");
        request.setTotalBudget(100000L);
        request.setOperator("admin");

        when(budgetPoolMapper.selectByActivityCode("DOUBLE11"))
                .thenReturn(createTestPool(1L, "DOUBLE11", 100000L, 0L, 1));

        assertThrows(BusinessException.class, () -> budgetPoolService.createPool(request));
        verify(budgetPoolMapper, never()).insert(any());
    }

    @Test
    void testOccupyBudget_Success() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 50000L, 1);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(budgetPoolMapper.occupyBudget(1L, 100L)).thenReturn(1);

        assertDoesNotThrow(() -> budgetPoolService.occupyBudget(
                1L, 1001L, 100L, "evt-001", "ORD-001"));

        verify(budgetPoolMapper).occupyBudget(1L, 100L);
        verify(budgetUsageLogMapper).insert(any(BudgetUsageLog.class));
    }

    @Test
    void testOccupyBudget_ExhaustedBudget_ThrowsException() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 99999L, 1);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(budgetPoolMapper.occupyBudget(1L, 100L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> budgetPoolService.occupyBudget(
                1L, 1001L, 100L, "evt-001", "ORD-001"));
    }

    @Test
    void testOccupyBudget_DailyLimitExceeded_ThrowsException() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 50000L, 1);
        pool.setDailyLimit(1000L);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(budgetUsageLogMapper.sumDailyOccupied(1L)).thenReturn(950L);

        assertThrows(BusinessException.class, () -> budgetPoolService.occupyBudget(
                1L, 1001L, 100L, "evt-001", "ORD-001"));

        verify(budgetPoolMapper, never()).occupyBudget(anyLong(), anyLong());
    }

    @Test
    void testOccupyBudget_MonthlyLimitExceeded_ThrowsException() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 50000L, 1);
        pool.setMonthlyLimit(5000L);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(budgetUsageLogMapper.sumMonthlyOccupied(1L)).thenReturn(4950L);

        assertThrows(BusinessException.class, () -> budgetPoolService.occupyBudget(
                1L, 1001L, 100L, "evt-001", "ORD-001"));

        verify(budgetPoolMapper, never()).occupyBudget(anyLong(), anyLong());
    }

    @Test
    void testOccupyBudget_PoolDisabled_ThrowsException() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 0L, 0);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);

        assertThrows(BusinessException.class, () -> budgetPoolService.occupyBudget(
                1L, 1001L, 100L, "evt-001", "ORD-001"));
    }

    @Test
    void testOccupyBudget_PoolCircuitBroken_ThrowsException() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 0L, 2);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);

        assertThrows(BusinessException.class, () -> budgetPoolService.occupyBudget(
                1L, 1001L, 100L, "evt-001", "ORD-001"));
    }

    @Test
    void testOccupyBudget_LockNotAcquired_ThrowsException() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        assertThrows(BusinessException.class, () -> budgetPoolService.occupyBudget(
                1L, 1001L, 100L, "evt-001", "ORD-001"));
    }

    @Test
    void testReleaseBudget_Success() {
        when(budgetPoolMapper.releaseBudget(1L, 100L)).thenReturn(1);

        assertDoesNotThrow(() -> budgetPoolService.releaseBudget(
                1L, 1001L, 100L, "refund-001", "ORD-001"));

        verify(budgetPoolMapper).releaseBudget(1L, 100L);
        verify(budgetUsageLogMapper).insert(any(BudgetUsageLog.class));
    }

    @Test
    void testReleaseBudget_RefundBackToPool() {
        when(budgetPoolMapper.releaseBudget(1L, 200L)).thenReturn(1);

        budgetPoolService.releaseBudget(1L, 1001L, 200L, "refund-002", "ORD-002");

        ArgumentCaptor<BudgetUsageLog> captor = ArgumentCaptor.forClass(BudgetUsageLog.class);
        verify(budgetUsageLogMapper).insert(captor.capture());
        assertEquals("REFUND", captor.getValue().getUsageType());
        assertEquals(200L, captor.getValue().getPoints());
    }

    @Test
    void testCheckCircuitBreak_TriggerAtThreshold() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 90000L, 1);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);

        assertTrue(budgetPoolService.checkCircuitBreak(1L));
    }

    @Test
    void testCheckCircuitBreak_BelowThreshold() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 80000L, 1);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);

        assertFalse(budgetPoolService.checkCircuitBreak(1L));
    }

    @Test
    void testRecoverPool_FromCircuitBroken() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 90000L, 2);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);

        assertDoesNotThrow(() -> budgetPoolService.recoverPool(1L, "admin"));
        verify(budgetPoolMapper).updateStatus(1L, BudgetPoolStatus.ENABLED.getCode());
    }

    @Test
    void testRecoverPool_NotCircuitBroken_ThrowsException() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 50000L, 1);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);

        assertThrows(BusinessException.class, () -> budgetPoolService.recoverPool(1L, "admin"));
    }

    @Test
    void testOccupyBudget_AccurateUsageLog() {
        BudgetPool pool = createTestPool(1L, "DOUBLE11", 100000L, 50000L, 1);
        when(budgetPoolMapper.selectById(1L)).thenReturn(pool);
        when(budgetPoolMapper.occupyBudget(1L, 500L)).thenReturn(1);

        budgetPoolService.occupyBudget(1L, 2001L, 500L, "evt-acc-001", "ORD-ACC");

        ArgumentCaptor<BudgetUsageLog> captor = ArgumentCaptor.forClass(BudgetUsageLog.class);
        verify(budgetUsageLogMapper).insert(captor.capture());
        BudgetUsageLog log = captor.getValue();
        assertEquals(1L, log.getPoolId());
        assertEquals(2001L, log.getMemberId());
        assertEquals(500L, log.getPoints());
        assertEquals("OCCUPY", log.getUsageType());
        assertEquals("BUDGET_OCCUPY_evt-acc-001", log.getEventId());
    }
}
