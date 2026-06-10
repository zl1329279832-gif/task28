package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.entity.PointsFreeze;
import com.example.points.enums.FreezeStatus;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.scheduled.PointsExpireTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsExpireTaskTest {

    @InjectMocks
    private PointsExpireTask expireTask;

    @Mock private PointsAccountMapper pointsAccountMapper;
    @Mock private PointsFlowMapper pointsFlowMapper;
    @Mock private PointsFreezeMapper pointsFreezeMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private PointsFlowService flowService;
    @Mock private RedissonClient redissonClient;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        // TransactionTemplate: synchronously invoke the callback
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void testExpirePoints_Success_SingleMember() {
        PointsFlow expiredFlow = PointsFlow.builder()
                .memberId(1001L)
                .eventType("REGISTER")
                .pointsChange(100L)
                .expireTime(LocalDateTime.now().minusHours(1))
                .build();
        when(pointsFlowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(expiredFlow))
                .thenReturn(Collections.emptyList());
        when(pointsAccountMapper.expirePoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(400L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        expireTask.expirePoints();

        verify(pointsAccountMapper).expirePoints(1001L, 100L);
        verify(pointsFlowMapper).insert(any(PointsFlow.class));
    }

    @Test
    void testExpirePoints_LockNotAcquired_SkipsExecution() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        expireTask.expirePoints();

        verify(pointsFlowMapper, never()).selectList(any());
        verify(pointsAccountMapper, never()).expirePoints(anyLong(), anyLong());
    }

    @Test
    void testExpirePoints_DeterministicEventId() {
        PointsFlow expiredFlow = PointsFlow.builder()
                .memberId(1001L)
                .eventType("PURCHASE")
                .pointsChange(200L)
                .expireTime(LocalDateTime.now().minusHours(2))
                .build();
        when(pointsFlowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(expiredFlow))
                .thenReturn(Collections.emptyList());
        when(pointsAccountMapper.expirePoints(1001L, 200L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(300L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        expireTask.expirePoints();

        String expectedEventId = "EXPIRE_1001_" + LocalDate.now().toString();
        org.mockito.ArgumentCaptor<PointsFlow> captor = org.mockito.ArgumentCaptor.forClass(PointsFlow.class);
        verify(pointsFlowMapper).insert(captor.capture());
        assertEquals(expectedEventId, captor.getValue().getEventId());
    }

    @Test
    void testExpirePoints_AlreadyProcessed_Idempotent() {
        PointsFlow expiredFlow = PointsFlow.builder()
                .memberId(1001L)
                .eventType("REGISTER")
                .pointsChange(100L)
                .build();
        when(pointsFlowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(expiredFlow))
                .thenReturn(Collections.emptyList());

        String eventId = "EXPIRE_1001_" + LocalDate.now().toString();
        PointsFlow existing = PointsFlow.builder().eventId(eventId).build();
        when(flowService.checkIdempotent(eventId)).thenReturn(existing);

        expireTask.expirePoints();

        verify(pointsAccountMapper, never()).expirePoints(anyLong(), anyLong());
    }

    @Test
    void testExpirePoints_InsufficientBalance_SkipsMember() {
        PointsFlow expiredFlow = PointsFlow.builder()
                .memberId(1001L)
                .eventType("REGISTER")
                .pointsChange(100L)
                .build();
        when(pointsFlowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(expiredFlow))
                .thenReturn(Collections.emptyList());
        when(pointsAccountMapper.expirePoints(1001L, 100L)).thenReturn(0);

        expireTask.expirePoints();

        // No flow created for this member
        verify(pointsFlowMapper, never()).insert(any(PointsFlow.class));
    }

    @Test
    void testExpirePoints_MultipleMembersInBatch() {
        PointsFlow flow1 = PointsFlow.builder().memberId(1001L).eventType("REGISTER").pointsChange(100L).build();
        PointsFlow flow2 = PointsFlow.builder().memberId(1002L).eventType("PURCHASE").pointsChange(200L).build();
        when(pointsFlowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(flow1, flow2))
                .thenReturn(Collections.emptyList());
        when(pointsAccountMapper.expirePoints(anyLong(), anyLong())).thenReturn(1);

        PointsAccount acc1 = new PointsAccount();
        acc1.setMemberId(1001L);
        acc1.setAvailablePoints(400L);
        PointsAccount acc2 = new PointsAccount();
        acc2.setMemberId(1002L);
        acc2.setAvailablePoints(800L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(acc1);
        when(pointsAccountMapper.selectByMemberId(1002L)).thenReturn(acc2);

        expireTask.expirePoints();

        verify(pointsAccountMapper).expirePoints(1001L, 100L);
        verify(pointsAccountMapper).expirePoints(1002L, 200L);
    }

    @Test
    void testExpirePoints_EmptyBatch_NoProcessing() {
        when(pointsFlowMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        expireTask.expirePoints();

        verify(pointsAccountMapper, never()).expirePoints(anyLong(), anyLong());
    }

    // --- autoUnfreezeExpired tests ---

    @Test
    void testAutoUnfreeze_Success() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-001")
                .points(100L)
                .bizOrderNo("ORD-001")
                .status(FreezeStatus.FROZEN.getCode())
                .expireTime(LocalDateTime.now().minusHours(1))
                .build();
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(freeze));
        when(pointsFreezeMapper.selectById(1L)).thenReturn(freeze);
        when(pointsAccountMapper.unfreezePoints(1001L, 100L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(600L);
        when(pointsAccountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        expireTask.autoUnfreezeExpired();

        verify(pointsAccountMapper).unfreezePoints(1001L, 100L);
        assertEquals(FreezeStatus.EXPIRED.getCode(), freeze.getStatus());
        verify(pointsFlowMapper).insert(any(PointsFlow.class));
    }

    @Test
    void testAutoUnfreeze_LockNotAcquired_SkipsExecution() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        expireTask.autoUnfreezeExpired();

        verify(pointsFreezeMapper, never()).selectList(any());
    }

    @Test
    void testAutoUnfreeze_AlreadyUnfrozen_SkipsRecord() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-002")
                .points(100L)
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(freeze));

        // Re-check: already unfrozen
        PointsFreeze unfrozen = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-002")
                .points(100L)
                .status(FreezeStatus.UNFROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectById(1L)).thenReturn(unfrozen);

        expireTask.autoUnfreezeExpired();

        verify(pointsAccountMapper, never()).unfreezePoints(anyLong(), anyLong());
    }

    @Test
    void testAutoUnfreeze_IdempotentFlowCheck() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-003")
                .points(100L)
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(freeze));
        when(pointsFreezeMapper.selectById(1L)).thenReturn(freeze);

        PointsFlow existing = PointsFlow.builder().eventId("AUTO_UNFREEZE_FZ-003").build();
        when(flowService.checkIdempotent("AUTO_UNFREEZE_FZ-003")).thenReturn(existing);

        expireTask.autoUnfreezeExpired();

        verify(pointsAccountMapper, never()).unfreezePoints(anyLong(), anyLong());
    }

    @Test
    void testAutoUnfreeze_ConflictWithManualUnfreeze() {
        PointsFreeze freeze = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-004")
                .points(100L)
                .status(FreezeStatus.FROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(freeze));

        // selectById returns UNFROZEN (manual unfreeze happened between query and processing)
        PointsFreeze manuallyUnfrozen = PointsFreeze.builder()
                .id(1L)
                .memberId(1001L)
                .freezeNo("FZ-004")
                .points(100L)
                .status(FreezeStatus.UNFROZEN.getCode())
                .build();
        when(pointsFreezeMapper.selectById(1L)).thenReturn(manuallyUnfrozen);

        expireTask.autoUnfreezeExpired();

        verify(pointsAccountMapper, never()).unfreezePoints(anyLong(), anyLong());
    }

    @Test
    void testExpirePoints_MemberExceptionDoesNotStopOthers() {
        PointsFlow flow1 = PointsFlow.builder().memberId(1001L).eventType("REGISTER").pointsChange(100L).build();
        PointsFlow flow2 = PointsFlow.builder().memberId(1002L).eventType("PURCHASE").pointsChange(200L).build();
        when(pointsFlowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(flow1, flow2))
                .thenReturn(Collections.emptyList());

        // First member: checkIdempotent throws
        String eventId1 = "EXPIRE_1001_" + LocalDate.now().toString();
        when(flowService.checkIdempotent(eq(eventId1))).thenThrow(new RuntimeException("DB error"));

        // Second member: processes normally
        when(pointsAccountMapper.expirePoints(1002L, 200L)).thenReturn(1);
        PointsAccount acc2 = new PointsAccount();
        acc2.setMemberId(1002L);
        acc2.setAvailablePoints(800L);
        when(pointsAccountMapper.selectByMemberId(1002L)).thenReturn(acc2);

        expireTask.expirePoints();

        // Second member should still be processed
        verify(pointsAccountMapper).expirePoints(1002L, 200L);
    }
}
