package com.membership.points.concurrent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membership.points.common.exception.ConcurrencyConflictException;
import com.membership.points.common.util.RedisLockUtil;
import com.membership.points.dto.request.RedeemPointsRequest;
import com.membership.points.entity.Benefit;
import com.membership.points.entity.BenefitInventory;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.mapper.BenefitInventoryMapper;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.mapper.RedemptionOrderMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.BenefitService;
import com.membership.points.service.BlacklistService;
import com.membership.points.service.CacheService;
import com.membership.points.service.IdempotentService;
import com.membership.points.service.MemberLevelService;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.impl.RedemptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedemptionConcurrencyTest {

    @Mock
    private IdempotentService idempotentService;

    @Mock
    private BlacklistService blacklistService;

    @Mock
    private BenefitService benefitService;

    @Mock
    private PointsAccountService pointsAccountService;

    @Mock
    private MemberLevelService memberLevelService;

    @Mock
    private BenefitInventoryMapper benefitInventoryMapper;

    @Mock
    private PointsBatchMapper pointsBatchMapper;

    @Mock
    private PointsTransactionMapper pointsTransactionMapper;

    @Mock
    private RedemptionOrderMapper redemptionOrderMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CacheService cacheService;

    @Mock
    private RedisLockUtil redisLockUtil;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RedemptionServiceImpl redemptionService;

    @BeforeEach
    void setUp() {
        // Common stubs that are safe for concurrent access via lenient mode
        lenient().when(idempotentService.checkAndMark(anyString(), anyString())).thenReturn(true);
        lenient().when(blacklistService.isBlockedForRedeem(anyLong())).thenReturn(false);

        Benefit benefit = new Benefit();
        benefit.setId(10L);
        benefit.setBenefitName("并发测试权益");
        benefit.setPointsCost(100);
        benefit.setEnabled(1);
        lenient().when(benefitService.getBenefitById(10L)).thenReturn(benefit);

        BenefitInventory inventory = new BenefitInventory();
        inventory.setId(1L);
        inventory.setBenefitId(10L);
        inventory.setSkuCode("SKU-CONC");
        inventory.setAvailableStock(1);
        inventory.setVersion(1);
        lenient().when(benefitService.getInventoryBySkuCode("SKU-CONC")).thenReturn(inventory);

        // TransactionTemplate stub
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        PointsAccount account = new PointsAccount();
        account.setId(1L);
        account.setMemberId(1L);
        account.setAvailablePoints(1000L);
        account.setFrozenPoints(0L);
        account.setVersion(1);
        lenient().when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);

        lenient().when(benefitInventoryMapper.selectById(1L)).thenReturn(inventory);
        lenient().when(benefitInventoryMapper.updateById(any(BenefitInventory.class))).thenReturn(1);

        PointsBatch batch = new PointsBatch();
        batch.setId(1L);
        batch.setMemberId(1L);
        batch.setRemainingPoints(1000L);
        batch.setFrozenPoints(0L);
        lenient().when(pointsBatchMapper.selectActiveBatchesByMemberId(1L)).thenReturn(List.of(batch));
        lenient().when(pointsBatchMapper.updateById(any(PointsBatch.class))).thenReturn(1);
        lenient().when(pointsTransactionMapper.insert(any())).thenReturn(1);
        lenient().when(redemptionOrderMapper.insert(any())).thenReturn(1);
    }

    @Test
    void testConcurrentRedemption_onlyOneSucceeds() throws Exception {
        // Configure redisLockUtil.tryLock to return true only once (AtomicBoolean)
        AtomicBoolean lockAcquired = new AtomicBoolean(false);
        when(redisLockUtil.tryLock(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
            // Only the first thread to call this gets the lock
            return lockAcquired.compareAndSet(false, true);
        });

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize start
                    RedeemPointsRequest req = new RedeemPointsRequest();
                    req.setMemberId(1L);
                    req.setBenefitId(10L);
                    req.setSkuCode("SKU-CONC");
                    req.setQuantity(1);
                    req.setIdempotentKey("conc-key-" + index);

                    redemptionService.redeem(req);
                    successCount.incrementAndGet();
                } catch (ConcurrencyConflictException e) {
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // Other exceptions are not expected but count as failures
                    failureCount.incrementAndGet();
                }
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Wait for all futures to complete
        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdown();

        // Assert: exactly 1 success, 19 failures
        assertEquals(1, successCount.get(), "Exactly one thread should succeed");
        assertEquals(19, failureCount.get(), "All other threads should fail with ConcurrencyConflictException");
    }
}
