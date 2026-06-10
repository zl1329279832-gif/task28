package com.example.points.service;

import com.example.points.common.BusinessException;
import com.example.points.dto.FreezeRequest;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.entity.PointsFreeze;
import com.example.points.enums.FreezeStatus;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.service.impl.PointsFreezeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsFreezeServiceTest {

    @InjectMocks
    private PointsFreezeServiceImpl freezeService;

    @Mock private PointsAccountMapper pointsAccountMapper;
    @Mock private PointsFreezeMapper pointsFreezeMapper;
    @Mock private PointsFlowService pointsFlowService;
    @Mock private BlacklistService blacklistService;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock rLock;

    private PointsAccount account;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // Mock TransactionTemplate
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(1000L);
        account.setFrozenPoints(0L);
        account.setStatus(1);
    }

    // ======================== freeze tests ========================

    @Test
    void testFreeze_Success() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(200L);
        request.setFreezeNo("FZ-001");
        request.setBizOrderNo("ORD-001");
        request.setReason("预扣");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(pointsFreezeMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(pointsAccountMapper.selectOne(any())).thenReturn(account);
        when(pointsAccountMapper.freezePoints(1001L, 200L)).thenReturn(1);

        PointsFreeze result = freezeService.freeze(request);

        assertNotNull(result);
        assertEquals("FZ-001", result.getFreezeNo());
        assertEquals(200L, result.getPoints());
        assertEquals(FreezeStatus.FROZEN.getCode(), result.getStatus());
        verify(pointsAccountMapper).freezePoints(1001L, 200L);
        verify(pointsFlowService).saveFlow(any(PointsFlow.class));
    }

    @Test
    void testFreeze_Blacklisted_ThrowsException() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(200L);
        request.setFreezeNo("FZ-002");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);

        assertThrows(BusinessException.class, () -> freezeService.freeze(request));
        verify(pointsAccountMapper, never()).freezePoints(anyLong(), anyLong());
    }

    @Test
    void testFreeze_InsufficientPoints_ThrowsException() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(2000L);
        request.setFreezeNo("FZ-003");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(pointsFreezeMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(pointsAccountMapper.selectOne(any())).thenReturn(account);
        when(pointsAccountMapper.freezePoints(1001L, 2000L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> freezeService.freeze(request));
    }

    @Test
    void testFreeze_Duplicate_ReturnsExisting() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(200L);
        request.setFreezeNo("FZ-DUP");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);

        PointsFreeze existing = new PointsFreeze();
        existing.setFreezeNo("FZ-DUP");
        existing.setPoints(200L);
        existing.setStatus(FreezeStatus.FROZEN.getCode());
        when(pointsFreezeMapper.selectList(any())).thenReturn(List.of(existing));

        PointsFreeze result = freezeService.freeze(request);

        assertNotNull(result);
        assertEquals("FZ-DUP", result.getFreezeNo());
        verify(pointsAccountMapper, never()).freezePoints(anyLong(), anyLong());
    }

    // ======================== unfreeze tests ========================

    @Test
    void testUnfreeze_Success_UsesCAS() {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(1L);
        freeze.setMemberId(1001L);
        freeze.setFreezeNo("FZ-UF-001");
        freeze.setPoints(200L);
        freeze.setStatus(FreezeStatus.FROZEN.getCode());
        freeze.setBizOrderNo("ORD-001");
        when(pointsFreezeMapper.selectOne(any())).thenReturn(freeze);

        // CAS succeeds
        when(pointsFreezeMapper.updateStatusCAS("FZ-UF-001",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.UNFROZEN.getCode())).thenReturn(1);

        account.setAvailablePoints(800L);
        when(pointsAccountMapper.selectOne(any())).thenReturn(account);
        when(pointsAccountMapper.unfreezePoints(1001L, 200L)).thenReturn(1);

        freezeService.unfreeze("FZ-UF-001");

        verify(pointsFreezeMapper).updateStatusCAS("FZ-UF-001",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.UNFROZEN.getCode());
        verify(pointsAccountMapper).unfreezePoints(1001L, 200L);
        verify(pointsFlowService).saveFlow(any(PointsFlow.class));
    }

    @Test
    void testUnfreeze_CASFails_AlreadyProcessed() {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(1L);
        freeze.setMemberId(1001L);
        freeze.setFreezeNo("FZ-UF-002");
        freeze.setPoints(200L);
        freeze.setStatus(FreezeStatus.FROZEN.getCode());
        when(pointsFreezeMapper.selectOne(any())).thenReturn(freeze);

        // CAS fails — another thread already processed it
        when(pointsFreezeMapper.updateStatusCAS("FZ-UF-002",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.UNFROZEN.getCode())).thenReturn(0);

        freezeService.unfreeze("FZ-UF-002");

        // Account should NOT be modified
        verify(pointsAccountMapper, never()).unfreezePoints(anyLong(), anyLong());
        verify(pointsFlowService, never()).saveFlow(any());
    }

    @Test
    void testUnfreeze_NotFrozen_ThrowsException() {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setFreezeNo("FZ-UF-003");
        freeze.setStatus(FreezeStatus.UNFROZEN.getCode()); // Already unfrozen
        when(pointsFreezeMapper.selectOne(any())).thenReturn(freeze);

        assertThrows(BusinessException.class, () -> freezeService.unfreeze("FZ-UF-003"));
    }

    // ======================== settleFreeze tests ========================

    @Test
    void testSettleFreeze_Success_UsesCAS() {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(1L);
        freeze.setMemberId(1001L);
        freeze.setFreezeNo("FZ-ST-001");
        freeze.setPoints(300L);
        freeze.setStatus(FreezeStatus.FROZEN.getCode());
        freeze.setBizOrderNo("ORD-001");
        when(pointsFreezeMapper.selectOne(any())).thenReturn(freeze);

        // CAS succeeds
        when(pointsFreezeMapper.updateStatusCAS("FZ-ST-001",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.DEDUCTED.getCode())).thenReturn(1);

        PointsAccount acc = new PointsAccount();
        acc.setMemberId(1001L);
        acc.setFrozenPoints(300L);
        when(pointsAccountMapper.selectOne(any())).thenReturn(acc);
        when(pointsAccountMapper.deductFrozenPoints(1001L, 300L)).thenReturn(1);

        freezeService.settleFreeze("FZ-ST-001");

        verify(pointsFreezeMapper).updateStatusCAS("FZ-ST-001",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.DEDUCTED.getCode());
        verify(pointsAccountMapper).deductFrozenPoints(1001L, 300L);
    }

    @Test
    void testSettleFreeze_CASFails_AlreadyProcessed() {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(1L);
        freeze.setMemberId(1001L);
        freeze.setFreezeNo("FZ-ST-002");
        freeze.setPoints(300L);
        freeze.setStatus(FreezeStatus.FROZEN.getCode());
        when(pointsFreezeMapper.selectOne(any())).thenReturn(freeze);

        // CAS fails
        when(pointsFreezeMapper.updateStatusCAS("FZ-ST-002",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.DEDUCTED.getCode())).thenReturn(0);

        freezeService.settleFreeze("FZ-ST-002");

        verify(pointsAccountMapper, never()).deductFrozenPoints(anyLong(), anyLong());
    }

    @Test
    void testSettleFreeze_NotFrozen_ThrowsException() {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setFreezeNo("FZ-ST-003");
        freeze.setStatus(FreezeStatus.DEDUCTED.getCode()); // Already settled
        when(pointsFreezeMapper.selectOne(any())).thenReturn(freeze);

        assertThrows(BusinessException.class, () -> freezeService.settleFreeze("FZ-ST-003"));
    }

    @Test
    void testSettleFreeze_FreezeNotFound_ThrowsException() {
        when(pointsFreezeMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> freezeService.settleFreeze("FZ-NONEXIST"));
    }
}
