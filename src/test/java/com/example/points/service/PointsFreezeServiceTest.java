package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    @Mock private PointsAccountService accountService;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    // --- freeze() tests ---

    @Test
    void testFreeze_Success() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(100L);
        request.setFreezeNo("FZ-001");
        request.setBizOrderNo("ORD-001");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(1000L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(pointsAccountMapper.freezePoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(900L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        PointsFreeze result = freezeService.freeze(request);

        assertNotNull(result);
        assertEquals(FreezeStatus.FROZEN.getCode(), result.getStatus());
        verify(pointsAccountMapper).freezePoints(1001L, 100L);
        verify(pointsFlowService).saveFlow(any(PointsFlow.class));
    }

    @Test
    void testFreeze_InsufficientPoints() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(2000L);
        request.setFreezeNo("FZ-002");
        request.setBizOrderNo("ORD-002");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(1000L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(pointsAccountMapper.freezePoints(1001L, 2000L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> freezeService.freeze(request));
    }

    @Test
    void testFreeze_DuplicateFreezeNo_ReturnsExisting() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(100L);
        request.setFreezeNo("FZ-DUP");
        request.setBizOrderNo("ORD-DUP");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);

        PointsFreeze existing = PointsFreeze.builder()
                .freezeNo("FZ-DUP")
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(existing));

        PointsFreeze result = freezeService.freeze(request);
        assertEquals("FZ-DUP", result.getFreezeNo());
        verify(pointsAccountMapper, never()).freezePoints(anyLong(), anyLong());
    }

    @Test
    void testFreeze_Blacklisted() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(100L);
        request.setFreezeNo("FZ-BL");
        request.setBizOrderNo("ORD-BL");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);

        assertThrows(BusinessException.class, () -> freezeService.freeze(request));
    }

    @Test
    void testFreeze_AccountNotExists() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(100L);
        request.setFreezeNo("FZ-NOACC");
        request.setBizOrderNo("ORD-NOACC");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(accountService.getAccount(1001L)).thenThrow(new BusinessException("积分账户不存在"));

        assertThrows(BusinessException.class, () -> freezeService.freeze(request));
    }

    @Test
    void testFreeze_LockNotAcquired() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(100L);
        request.setFreezeNo("FZ-LOCK");
        request.setBizOrderNo("ORD-LOCK");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () -> freezeService.freeze(request));
    }

    @Test
    void testFreeze_AccurateBeforeAfterPoints() {
        FreezeRequest request = new FreezeRequest();
        request.setMemberId(1001L);
        request.setPoints(100L);
        request.setFreezeNo("FZ-ACC");
        request.setBizOrderNo("ORD-ACC");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(pointsAccountMapper.freezePoints(1001L, 100L)).thenReturn(1);

        // After freeze, available = 900
        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(900L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        freezeService.freeze(request);

        // Verify flow: beforePoints = 900 + 100 = 1000, afterPoints = 900
        org.mockito.ArgumentCaptor<PointsFlow> captor = org.mockito.ArgumentCaptor.forClass(PointsFlow.class);
        verify(pointsFlowService).saveFlow(captor.capture());
        PointsFlow flow = captor.getValue();
        assertEquals(1000L, flow.getBeforePoints());
        assertEquals(900L, flow.getAfterPoints());
        assertEquals(-100L, flow.getPointsChange());
    }

    // --- unfreeze() tests ---

    @Test
    void testUnfreeze_Success() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-U001")
                .points(100L)
                .bizOrderNo("ORD-U001")
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(freeze);
        when(pointsAccountMapper.unfreezePoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(600L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        freezeService.unfreeze("FZ-U001");

        verify(pointsAccountMapper).unfreezePoints(1001L, 100L);
        assertEquals(FreezeStatus.UNFROZEN.getCode(), freeze.getStatus());
        verify(pointsFlowService).saveFlow(any(PointsFlow.class));
    }

    @Test
    void testUnfreeze_AlreadyUnfrozen_Noop() {
        // First call returns FROZEN (pre-lock check), second call (inside lock) returns UNFROZEN
        PointsFreeze frozenRecord = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-U002")
                .points(100L)
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        PointsFreeze unfrozenRecord = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-U002")
                .points(100L)
                .status(FreezeStatus.UNFROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(frozenRecord)
                .thenReturn(unfrozenRecord);

        freezeService.unfreeze("FZ-U002");

        verify(pointsAccountMapper, never()).unfreezePoints(anyLong(), anyLong());
    }

    @Test
    void testUnfreeze_FreezeNotFound() {
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThrows(BusinessException.class, () -> freezeService.unfreeze("FZ-NONE"));
    }

    @Test
    void testUnfreeze_AccurateBeforeAfterPoints() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-UACC")
                .points(100L)
                .bizOrderNo("ORD-UACC")
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(freeze);
        when(pointsAccountMapper.unfreezePoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(600L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        freezeService.unfreeze("FZ-UACC");

        org.mockito.ArgumentCaptor<PointsFlow> captor = org.mockito.ArgumentCaptor.forClass(PointsFlow.class);
        verify(pointsFlowService).saveFlow(captor.capture());
        PointsFlow flow = captor.getValue();
        // beforePoints = 600 - 100 = 500, afterPoints = 600
        assertEquals(500L, flow.getBeforePoints());
        assertEquals(600L, flow.getAfterPoints());
        assertEquals(100L, flow.getPointsChange());
    }

    // --- settleFreeze() tests ---

    @Test
    void testSettleFreeze_Success() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-S001")
                .points(100L)
                .bizOrderNo("ORD-S001")
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(freeze);
        when(pointsAccountMapper.deductFrozenPoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setFrozenPoints(0L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        freezeService.settleFreeze("FZ-S001");

        verify(pointsAccountMapper).deductFrozenPoints(1001L, 100L);
        assertEquals(FreezeStatus.DEDUCTED.getCode(), freeze.getStatus());
        verify(pointsFlowService).saveFlow(any(PointsFlow.class));
    }

    @Test
    void testSettleFreeze_AlreadySettled_Noop() {
        PointsFreeze frozenRecord = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-S002")
                .points(100L)
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        PointsFreeze deductedRecord = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-S002")
                .points(100L)
                .status(FreezeStatus.DEDUCTED.getCode())
                .build();
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(frozenRecord)
                .thenReturn(deductedRecord);

        freezeService.settleFreeze("FZ-S002");

        verify(pointsAccountMapper, never()).deductFrozenPoints(anyLong(), anyLong());
    }

    @Test
    void testSettleFreeze_DeductFrozenFails() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-S003")
                .points(100L)
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(freeze);
        when(pointsAccountMapper.deductFrozenPoints(1001L, 100L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> freezeService.settleFreeze("FZ-S003"));
    }

    @Test
    void testSettleFreeze_AccurateBeforeAfterFrozenPoints() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-SACC")
                .points(100L)
                .bizOrderNo("ORD-SACC")
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(freeze);
        when(pointsAccountMapper.deductFrozenPoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setFrozenPoints(200L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        freezeService.settleFreeze("FZ-SACC");

        org.mockito.ArgumentCaptor<PointsFlow> captor = org.mockito.ArgumentCaptor.forClass(PointsFlow.class);
        verify(pointsFlowService).saveFlow(captor.capture());
        PointsFlow flow = captor.getValue();
        // beforeFrozen = 200 + 100 = 300, afterFrozen = 200
        assertEquals(300L, flow.getBeforePoints());
        assertEquals(200L, flow.getAfterPoints());
        assertEquals(-100L, flow.getPointsChange());
    }
}
