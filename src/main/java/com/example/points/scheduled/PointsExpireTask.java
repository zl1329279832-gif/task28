package com.example.points.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.entity.PointsFreeze;
import com.example.points.enums.FreezeStatus;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointsExpireTask {

    private final PointsAccountMapper pointsAccountMapper;
    private final PointsFlowMapper pointsFlowMapper;
    private final PointsFreezeMapper pointsFreezeMapper;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * Expire points: run at 2 AM daily.
     * Processes credit flows (REGISTER, PURCHASE, CHECKIN, ACTIVITY, REFUND)
     * that have expired within the last 24 hours.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void expirePoints() {
        // Distributed lock to prevent cluster double-processing
        RLock lock = redissonClient.getLock("lock:task:expire_points");
        try {
            if (!lock.tryLock(0, 3600, TimeUnit.SECONDS)) {
                log.info("expirePoints task already running on another instance, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("expirePoints lock acquisition interrupted");
            return;
        }

        try {
            log.info("Starting points expiration task");
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime oneDayAgo = now.minusDays(1);
            List<String> creditEventTypes = Arrays.asList("REGISTER", "PURCHASE", "CHECKIN", "ACTIVITY", "REFUND");

            int batchSize = 1000;
            int page = 0;
            int totalProcessed = 0;

            while (true) {
                // Rebuild wrapper each iteration to avoid appending multiple LIMIT clauses
                LambdaQueryWrapper<PointsFlow> wrapper = new LambdaQueryWrapper<>();
                wrapper.in(PointsFlow::getEventType, creditEventTypes)
                        .gt(PointsFlow::getPointsChange, 0)
                        .le(PointsFlow::getExpireTime, now)
                        .ge(PointsFlow::getExpireTime, oneDayAgo)
                        .last("LIMIT " + batchSize + " OFFSET " + (page * batchSize));

                List<PointsFlow> expiredFlows = pointsFlowMapper.selectList(wrapper);
                if (expiredFlows.isEmpty()) {
                    break;
                }

                // Group by member_id
                Map<Long, List<PointsFlow>> groupedByMember = expiredFlows.stream()
                        .collect(Collectors.groupingBy(PointsFlow::getMemberId));

                for (Map.Entry<Long, List<PointsFlow>> entry : groupedByMember.entrySet()) {
                    Long memberId = entry.getKey();
                    List<PointsFlow> memberFlows = entry.getValue();

                    try {
                        long sumExpired = memberFlows.stream()
                                .mapToLong(PointsFlow::getPointsChange)
                                .sum();

                        // Use per-member lock + transaction for atomicity
                        RLock memberLock = redissonClient.getLock("lock:points:event:" + memberId);
                        if (!memberLock.tryLock(5, 30, TimeUnit.SECONDS)) {
                            log.warn("Failed to acquire lock for memberId={}, skipping expiration", memberId);
                            continue;
                        }
                        try {
                            transactionTemplate.executeWithoutResult(status -> {
                                int rows = pointsAccountMapper.expirePoints(memberId, sumExpired);
                                if (rows > 0) {
                                    LambdaQueryWrapper<PointsAccount> accountWrapper = new LambdaQueryWrapper<>();
                                    accountWrapper.eq(PointsAccount::getMemberId, memberId);
                                    PointsAccount account = pointsAccountMapper.selectOne(accountWrapper);
                                    long afterPoints = account != null ? account.getAvailablePoints() : 0;

                                    PointsFlow expireFlow = PointsFlow.builder()
                                            .memberId(memberId)
                                            .eventId("EXPIRE_" + memberId + "_" + System.currentTimeMillis())
                                            .eventType("EXPIRE")
                                            .pointsChange(-sumExpired)
                                            .beforePoints(afterPoints + sumExpired)
                                            .afterPoints(afterPoints)
                                            .remark("积分过期，过期积分: " + sumExpired)
                                            .createTime(LocalDateTime.now())
                                            .build();
                                    pointsFlowMapper.insert(expireFlow);

                                    auditLogService.log("POINTS", "EXPIRE", String.valueOf(memberId), "MEMBER",
                                            String.valueOf(afterPoints + sumExpired), String.valueOf(afterPoints),
                                            "SYSTEM", null);

                                    log.info("Points expired: memberId={}, expiredPoints={}", memberId, sumExpired);
                                }
                            });
                            totalProcessed++;
                        } finally {
                            if (memberLock.isHeldByCurrentThread()) {
                                memberLock.unlock();
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error processing expiration for memberId={}", memberId, e);
                    }
                }

                if (expiredFlows.size() < batchSize) {
                    break;
                }
                page++;
            }

            log.info("Points expiration task completed, total members processed: {}", totalProcessed);
        } catch (Exception e) {
            log.error("Points expiration task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Auto-unfreeze expired freezes: run every 10 minutes.
     * Finds frozen records past their expiration time and unfreezes the points.
     * Uses CAS on freeze status to prevent race with manual unfreeze/settle.
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void autoUnfreezeExpired() {
        // Distributed lock to prevent cluster double-processing
        RLock lock = redissonClient.getLock("lock:task:auto_unfreeze");
        try {
            if (!lock.tryLock(0, 600, TimeUnit.SECONDS)) {
                log.info("autoUnfreezeExpired task already running on another instance, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("autoUnfreezeExpired lock acquisition interrupted");
            return;
        }

        try {
            log.info("Starting auto-unfreeze expired task");
            LocalDateTime now = LocalDateTime.now();

            LambdaQueryWrapper<PointsFreeze> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PointsFreeze::getStatus, FreezeStatus.FROZEN.getCode())
                    .le(PointsFreeze::getExpireTime, now)
                    .last("LIMIT 500");

            List<PointsFreeze> expiredFreezes = pointsFreezeMapper.selectList(wrapper);

            int totalProcessed = 0;
            for (PointsFreeze freeze : expiredFreezes) {
                try {
                    // Acquire per-member freeze lock
                    RLock memberLock = redissonClient.getLock("lock:freeze:" + freeze.getMemberId());
                    if (!memberLock.tryLock(5, 30, TimeUnit.SECONDS)) {
                        log.warn("Failed to acquire lock for freeze memberId={}, skipping", freeze.getMemberId());
                        continue;
                    }
                    try {
                        final Long memberId = freeze.getMemberId();
                        final Long frozenPoints = freeze.getPoints();
                        final String freezeNo = freeze.getFreezeNo();
                        final String bizOrderNo = freeze.getBizOrderNo();

                        transactionTemplate.executeWithoutResult(status -> {
                            // CAS status transition: FROZEN → EXPIRED
                            int casRows = pointsFreezeMapper.updateStatusCAS(
                                    freezeNo, FreezeStatus.FROZEN.getCode(), FreezeStatus.EXPIRED.getCode());
                            if (casRows == 0) {
                                log.info("Freeze already processed (CAS failed), freezeNo={}", freezeNo);
                                return;
                            }

                            // Unfreeze points
                            int rows = pointsAccountMapper.unfreezePoints(memberId, frozenPoints);
                            if (rows <= 0) {
                                log.warn("unfreezePoints returned 0 for memberId={}, freezeNo={}", memberId, freezeNo);
                                return;
                            }

                            // Read updated account for flow record
                            LambdaQueryWrapper<PointsAccount> accountWrapper = new LambdaQueryWrapper<>();
                            accountWrapper.eq(PointsAccount::getMemberId, memberId);
                            PointsAccount account = pointsAccountMapper.selectOne(accountWrapper);
                            long afterPoints = account != null ? account.getAvailablePoints() : 0;

                            PointsFlow flow = PointsFlow.builder()
                                    .memberId(memberId)
                                    .eventId("AUTO_UNFREEZE_" + freezeNo)
                                    .eventType("UNFREEZE")
                                    .pointsChange(frozenPoints)
                                    .beforePoints(afterPoints - frozenPoints)
                                    .afterPoints(afterPoints)
                                    .bizOrderNo(bizOrderNo)
                                    .remark("自动解冻过期冻结积分，冻结单号: " + freezeNo)
                                    .createTime(LocalDateTime.now())
                                    .build();
                            pointsFlowMapper.insert(flow);

                            log.info("Auto-unfreeze: memberId={}, points={}, freezeNo={}",
                                    memberId, frozenPoints, freezeNo);
                        });
                        totalProcessed++;
                    } finally {
                        if (memberLock.isHeldByCurrentThread()) {
                            memberLock.unlock();
                        }
                    }
                } catch (Exception e) {
                    log.error("Error auto-unfreezing freezeNo={}", freeze.getFreezeNo(), e);
                }
            }

            log.info("Auto-unfreeze task completed, total processed: {}", totalProcessed);
        } catch (Exception e) {
            log.error("Auto-unfreeze task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Reset monthly earned counter: run on the first day of each month at 1 AM.
     * Resets the monthly_earned field for all accounts that haven't been reset yet.
     */
    @Scheduled(cron = "0 0 1 1 * ?")
    public void resetMonthlyEarned() {
        RLock lock = redissonClient.getLock("lock:task:reset_monthly");
        try {
            if (!lock.tryLock(0, 300, TimeUnit.SECONDS)) {
                log.info("resetMonthlyEarned task already running on another instance, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("resetMonthlyEarned lock acquisition interrupted");
            return;
        }

        try {
            log.info("Starting monthly earned reset task");
            LocalDate today = LocalDate.now();
            int rows = pointsAccountMapper.resetMonthlyEarned(today);
            log.info("Monthly earned reset completed, accounts reset: {}", rows);
        } catch (Exception e) {
            log.error("Monthly earned reset task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
