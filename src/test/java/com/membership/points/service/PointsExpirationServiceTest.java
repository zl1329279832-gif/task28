package com.membership.points.service;

import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.impl.PointsExpirationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsExpirationServiceTest {

    @Mock
    private PointsBatchMapper pointsBatchMapper;

    @Mock
    private PointsAccountService pointsAccountService;

    @Mock
    private PointsTransactionMapper pointsTransactionMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private PointsExpirationServiceImpl pointsExpirationService;

    private PointsAccount account;

    @BeforeEach
    void setUp() {
        account = new PointsAccount();
        account.setId(1L);
        account.setMemberId(1L);
        account.setAvailablePoints(500L);
        account.setFrozenPoints(0L);
        account.setVersion(1);
    }

    @Test
    void testExpireBatch_success() {
        // Arrange: 2 expired batches, each with remaining=100, frozen=0
        PointsBatch batch1 = new PointsBatch();
        batch1.setId(1L);
        batch1.setMemberId(1L);
        batch1.setRemainingPoints(100L);
        batch1.setFrozenPoints(0L);
        batch1.setExpired(0);
        batch1.setExpireAt(LocalDateTime.now().minusDays(1));

        PointsBatch batch2 = new PointsBatch();
        batch2.setId(2L);
        batch2.setMemberId(1L);
        batch2.setRemainingPoints(100L);
        batch2.setFrozenPoints(0L);
        batch2.setExpired(0);
        batch2.setExpireAt(LocalDateTime.now().minusDays(2));

        List<PointsBatch> expiredBatches = Arrays.asList(batch1, batch2);

        // selectList returns the expired batches for the initial query
        when(pointsBatchMapper.selectList(any())).thenReturn(expiredBatches);
        // selectById returns the fresh batch for processExpireBatch
        when(pointsBatchMapper.selectById(1L)).thenReturn(batch1);
        when(pointsBatchMapper.selectById(2L)).thenReturn(batch2);
        when(pointsBatchMapper.updateById(any(PointsBatch.class))).thenReturn(1);
        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);
        when(pointsTransactionMapper.insert(any())).thenReturn(1);

        // Act
        int result = pointsExpirationService.expireBatch();

        // Assert
        assertEquals(2, result);
        verify(pointsAccountService, times(2)).expirePoints(eq(1L), eq(100L), anyInt());
        verify(cacheService).evictPointsAccount(1L);
    }

    @Test
    void testExpireBatch_skipFrozen() {
        // Arrange: batch with remaining=100, frozen=80 => only 20 points should expire
        PointsBatch batch = new PointsBatch();
        batch.setId(1L);
        batch.setMemberId(1L);
        batch.setRemainingPoints(100L);
        batch.setFrozenPoints(80L);
        batch.setExpired(0);
        batch.setExpireAt(LocalDateTime.now().minusDays(1));

        when(pointsBatchMapper.selectList(any())).thenReturn(List.of(batch));
        when(pointsBatchMapper.selectById(1L)).thenReturn(batch);
        when(pointsBatchMapper.updateById(any(PointsBatch.class))).thenReturn(1);
        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);
        when(pointsTransactionMapper.insert(any())).thenReturn(1);

        // Act
        int result = pointsExpirationService.expireBatch();

        // Assert
        assertEquals(1, result);
        // Only 20 points expired (remaining 100 - frozen 80 = 20)
        verify(pointsAccountService).expirePoints(eq(1L), eq(20L), anyInt());
    }

    @Test
    void testExpireBatch_noBatchesToExpire() {
        // Arrange: no expired batches
        when(pointsBatchMapper.selectList(any())).thenReturn(Collections.emptyList());

        // Act
        int result = pointsExpirationService.expireBatch();

        // Assert
        assertEquals(0, result);
        verify(pointsAccountService, never()).expirePoints(anyLong(), anyLong(), anyInt());
        verify(cacheService, never()).evictPointsAccount(anyLong());
    }
}
