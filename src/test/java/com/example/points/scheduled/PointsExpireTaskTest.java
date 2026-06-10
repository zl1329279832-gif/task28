package com.example.points.scheduled;

import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.entity.PointsFreeze;
import com.example.points.enums.FreezeStatus;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.service.AuditLogService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsExpireTaskTest {

    @InjectMocks
    private PointsExpireTask pointsExpireTask;

    @Mock private PointsAccountMapper pointsAccountMapper;
    @Mock private PointsFlowMapper pointsFlowMapper;
    @Mock private PointsFreezeMapper pointsFreezeMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock taskLock;
    @Mock private RLock memberLock;

    @BeforeEach
    void setUp() throws Exception {
        // Task-level locks
        lenient().when(redissonClient.getLock(startsWith("lock:task:"))).thenReturn(taskLock);
        lenient().when(taskLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(taskLock.isHeldByCurrentThread()).thenReturn(true);

        // Member-level locks
        lenient().when(redissonClient.getLock(startsWith("lock:points:"))).thenReturn(memberLock);
        lenient().when(redissonClient.getLock(startsWith("lock:freeze:"))).thenReturn(memberLock);
        lenient().when(memberLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(memberLock.isHeldByCurrentThread()).thenReturn(true);

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
    }

    // ======================== expirePoints tests ========================

    @Test
    void testExpirePoints_AcquiresDistributedLock() throws Exception {
        when(pointsFlowMapper.selectList(any())).thenReturn(Collections.emptyList());

        pointsExpireTask.expirePoints();

        verify(redissonClient).getLock("lock:task:expire_points");
        verify(taskLock).tryLock(0, 3600, TimeUnit.SECONDS);
    }

    @Test
    void testExpirePoints_SkipsWhenLockNotAcquired() throws Exception {
        when(taskLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        pointsExpireTask.expirePoints();

        verify(pointsFlowMapper, never()).selectList(any());
    }

    @Test
    void testExpirePoints_ProcessesExpiredFlows() throws Exception {
        PointsFlow flow1 = new PointsFlow();
        flow1.setMemberId(1001L);
        flow1.setPointsChange(100L);
        flow1.setEventType("PURCHASE");

        PointsFlow flow2 = new PointsFlow();
        flow2.setMemberId(1001L);
        flow2.setPointsChange(50L);
        flow2.setEventType("REGISTER");

        when(pointsFlowMapper.selectList(any()))
                .thenReturn(Arrays.asList(flow1, flow2))  // first batch
                .thenReturn(Collections.emptyList());       // no more

        when(pointsAccountMapper.expirePoints(1001L, 150L)).thenReturn(1);

        PointsAccount account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(850L);
        when(pointsAccountMapper.selectOne(any())).thenReturn(account);

        pointsExpireTask.expirePoints();

        verify(pointsAccountMapper).expirePoints(1001L, 150L);
        verify(pointsFlowMapper).insert(any(PointsFlow.class));
    }

    @Test
    void testExpirePoints_AcquiresPerMemberLock() throws Exception {
        PointsFlow flow = new PointsFlow();
        flow.setMemberId(2001L);
        flow.setPointsChange(100L);
        flow.setEventType("PURCHASE");

        when(pointsFlowMapper.selectList(any()))
                .thenReturn(List.of(flow))
                .thenReturn(Collections.emptyList());

        when(pointsAccountMapper.expirePoints(2001L, 100L)).thenReturn(1);
        PointsAccount account = new PointsAccount();
        account.setAvailablePoints(0L);
        when(pointsAccountMapper.selectOne(any())).thenReturn(account);

        pointsExpireTask.expirePoints();

        verify(redissonClient).getLock("lock:points:event:2001");
    }

    // ======================== autoUnfreezeExpired tests ========================

    @Test
    void testAutoUnfreezeExpired_AcquiresDistributedLock() throws Exception {
        when(pointsFreezeMapper.selectList(any())).thenReturn(Collections.emptyList());

        pointsExpireTask.autoUnfreezeExpired();

        verify(redissonClient).getLock("lock:task:auto_unfreeze");
    }

    @Test
    void testAutoUnfreezeExpired_SkipsWhenLockNotAcquired() throws Exception {
        when(taskLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        pointsExpireTask.autoUnfreezeExpired();

        verify(pointsFreezeMapper, never()).selectList(any());
    }

    @Test
    void testAutoUnfreezeExpired_UsesCASStatusUpdate() throws Exception {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(1L);
        freeze.setMemberId(1001L);
        freeze.setFreezeNo("FZ-EXP-001");
        freeze.setPoints(200L);
        freeze.setStatus(FreezeStatus.FROZEN.getCode());
        freeze.setBizOrderNo("ORD-001");
        freeze.setExpireTime(LocalDateTime.now().minusHours(1));

        when(pointsFreezeMapper.selectList(any())).thenReturn(List.of(freeze));

        // CAS succeeds
        when(pointsFreezeMapper.updateStatusCAS("FZ-EXP-001",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.EXPIRED.getCode())).thenReturn(1);

        when(pointsAccountMapper.unfreezePoints(1001L, 200L)).thenReturn(1);

        PointsAccount account = new PointsAccount();
        account.setAvailablePoints(1200L);
        when(pointsAccountMapper.selectOne(any())).thenReturn(account);

        pointsExpireTask.autoUnfreezeExpired();

        // Verify CAS was used instead of updateById
        verify(pointsFreezeMapper).updateStatusCAS("FZ-EXP-001",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.EXPIRED.getCode());
        verify(pointsFreezeMapper, never()).updateById(any());
        verify(pointsAccountMapper).unfreezePoints(1001L, 200L);
    }

    @Test
    void testAutoUnfreezeExpired_CASFails_SkipsRecord() throws Exception {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(1L);
        freeze.setMemberId(1001L);
        freeze.setFreezeNo("FZ-EXP-002");
        freeze.setPoints(200L);
        freeze.setStatus(FreezeStatus.FROZEN.getCode());
        freeze.setExpireTime(LocalDateTime.now().minusHours(1));

        when(pointsFreezeMapper.selectList(any())).thenReturn(List.of(freeze));

        // CAS fails — another operation (settle/unfreeze) already processed it
        when(pointsFreezeMapper.updateStatusCAS("FZ-EXP-002",
                FreezeStatus.FROZEN.getCode(), FreezeStatus.EXPIRED.getCode())).thenReturn(0);

        pointsExpireTask.autoUnfreezeExpired();

        // Should NOT unfreeze points
        verify(pointsAccountMapper, never()).unfreezePoints(anyLong(), anyLong());
    }

    @Test
    void testAutoUnfreezeExpired_AcquiresPerMemberFreezeLock() throws Exception {
        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(1L);
        freeze.setMemberId(3001L);
        freeze.setFreezeNo("FZ-EXP-003");
        freeze.setPoints(100L);
        freeze.setStatus(FreezeStatus.FROZEN.getCode());
        freeze.setBizOrderNo("ORD-003");
        freeze.setExpireTime(LocalDateTime.now().minusHours(1));

        when(pointsFreezeMapper.selectList(any())).thenReturn(List.of(freeze));
        when(pointsFreezeMapper.updateStatusCAS(anyString(), anyInt(), anyInt())).thenReturn(1);
        when(pointsAccountMapper.unfreezePoints(3001L, 100L)).thenReturn(1);

        PointsAccount account = new PointsAccount();
        account.setAvailablePoints(100L);
        when(pointsAccountMapper.selectOne(any())).thenReturn(account);

        pointsExpireTask.autoUnfreezeExpired();

        verify(redissonClient).getLock("lock:freeze:3001");
    }

    // ======================== resetMonthlyEarned tests ========================

    @Test
    void testResetMonthlyEarned_AcquiresDistributedLock() throws Exception {
        pointsExpireTask.resetMonthlyEarned();

        verify(redissonClient).getLock("lock:task:reset_monthly");
        verify(pointsAccountMapper).resetMonthlyEarned(any(LocalDate.class));
    }

    @Test
    void testResetMonthlyEarned_SkipsWhenLockNotAcquired() throws Exception {
        when(taskLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        pointsExpireTask.resetMonthlyEarned();

        verify(pointsAccountMapper, never()).resetMonthlyEarned(any());
    }
}
