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
import com.example.points.service.PointsFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
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
    private final PointsFlowService flowService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final com.example.points.service.BudgetPoolService budgetPoolService;

    /**
     * Expire points: run at 2 AM daily.
     * Processes credit flows (REGISTER, PURCHASE, CHECKIN, ACTIVITY, REFUND)
     * that have expired within the last 24 hours.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void expirePoints() {
        log.info("Starting points expiration task");

        String lockKey = "lock:points:expire:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 30, TimeUnit.MINUTES)) {
                log.info("Another instance is running expirePoints, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring expirePoints lock", e);
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime oneDayAgo = now.minusDays(1);

            // Query flows where expire_time <= NOW() and expire_time >= NOW() - 1 day
            // Only credit flows (positive points_change)
            List<String> creditEventTypes = Arrays.asList("REGISTER", "PURCHASE", "CHECKIN", "ACTIVITY", "REFUND");
            LambdaQueryWrapper<PointsFlow> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(PointsFlow::getEventType, creditEventTypes)
                    .gt(PointsFlow::getPointsChange, 0)
                    .le(PointsFlow::getExpireTime, now)
                    .ge(PointsFlow::getExpireTime, oneDayAgo);

            // Process in batches to avoid memory issues
            int batchSize = 1000;
            int page = 0;
            int totalProcessed = 0;

            while (true) {
                wrapper.last("LIMIT " + batchSize + " OFFSET " + (page * batchSize));
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
                        if (processMemberExpiration(memberId, memberFlows)) {
                            totalProcessed++;
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

    private boolean processMemberExpiration(Long memberId, List<PointsFlow> memberFlows) {
        // Deterministic eventId using today's date (NOT timestamp)
        String eventId = "EXPIRE_" + memberId + "_" + LocalDate.now().toString();

        // Idempotency check
        PointsFlow existing = flowService.checkIdempotent(eventId);
        if (existing != null) {
            log.info("Expiration already processed for memberId={} on {}", memberId, LocalDate.now());
            return false;
        }

        long sumExpired = memberFlows.stream()
                .mapToLong(PointsFlow::getPointsChange)
                .sum();

        try {
            Boolean result = transactionTemplate.execute(status -> {
                int rows = pointsAccountMapper.expirePoints(memberId, sumExpired);
                if (rows == 0) {
                    log.warn("Cannot expire points for memberId={}: insufficient available_points", memberId);
                    return false;
                }

                // Re-read account AFTER update for accurate flow values
                PointsAccount account = pointsAccountMapper.selectByMemberId(memberId);
                long afterPoints = account != null ? account.getAvailablePoints() : 0;
                long beforePoints = afterPoints + sumExpired;

                PointsFlow expireFlow = PointsFlow.builder()
                        .memberId(memberId)
                        .eventId(eventId)
                        .eventType("EXPIRE")
                        .pointsChange(-sumExpired)
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .remark("积分过期，过期积分: " + sumExpired)
                        .createTime(LocalDateTime.now())
                        .build();
                pointsFlowMapper.insert(expireFlow);

                auditLogService.log("POINTS", "EXPIRE", String.valueOf(memberId), "MEMBER",
                        String.valueOf(beforePoints), String.valueOf(afterPoints),
                        "SYSTEM", null);

                log.info("Points expired: memberId={}, expiredPoints={}", memberId, sumExpired);
                return true;
            });
            return result != null && result;
        } catch (DuplicateKeyException e) {
            log.info("Expiration flow already inserted for memberId={}, skipping", memberId);
            return false;
        }
    }

    /**
     * Auto-unfreeze expired freezes: run every 10 minutes.
     * Finds frozen records past their expiration time and unfreezes the points.
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void autoUnfreezeExpired() {
        log.info("Starting auto-unfreeze expired task");

        String lockKey = "lock:points:autounfreeze:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 10, TimeUnit.MINUTES)) {
                log.info("Another instance is running autoUnfreezeExpired, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring autoUnfreezeExpired lock", e);
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();

            // Query freezes where status=FROZEN(0) and expire_time <= NOW()
            LambdaQueryWrapper<PointsFreeze> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PointsFreeze::getStatus, FreezeStatus.FROZEN.getCode())
                    .le(PointsFreeze::getExpireTime, now);

            // Process in batches
            int batchSize = 500;
            wrapper.last("LIMIT " + batchSize);
            List<PointsFreeze> expiredFreezes = pointsFreezeMapper.selectList(wrapper);

            int totalProcessed = 0;
            for (PointsFreeze freeze : expiredFreezes) {
                try {
                    if (processAutoUnfreeze(freeze)) {
                        totalProcessed++;
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

    private boolean processAutoUnfreeze(PointsFreeze freeze) {
        // Re-check freeze status (conflict with manual unfreeze)
        PointsFreeze freshFreeze = pointsFreezeMapper.selectById(freeze.getId());
        if (freshFreeze == null || freshFreeze.getStatus() != FreezeStatus.FROZEN.getCode()) {
            log.info("Freeze already processed, freezeNo={}, status={}",
                    freeze.getFreezeNo(), freshFreeze != null ? freshFreeze.getStatus() : "deleted");
            return false;
        }

        // Deterministic eventId
        String eventId = "AUTO_UNFREEZE_" + freeze.getFreezeNo();

        // Idempotency check
        PointsFlow existing = flowService.checkIdempotent(eventId);
        if (existing != null) {
            log.info("Auto-unfreeze already processed, freezeNo={}", freeze.getFreezeNo());
            return false;
        }

        try {
            Boolean result = transactionTemplate.execute(status -> {
                int rows = pointsAccountMapper.unfreezePoints(freeze.getMemberId(), freeze.getPoints());
                if (rows == 0) {
                    log.warn("Cannot unfreeze: unfreezePoints returned 0 for freezeNo={}", freeze.getFreezeNo());
                    return false;
                }

                // Update freeze status to EXPIRED(3)
                freshFreeze.setStatus(FreezeStatus.EXPIRED.getCode());
                freshFreeze.setUpdateTime(LocalDateTime.now());
                pointsFreezeMapper.updateById(freshFreeze);

                // Unfreeze corresponding budget if linked to a budget pool
                if (freshFreeze.getBudgetPoolId() != null && freshFreeze.getBudgetAmount() != null) {
                    budgetPoolService.unfreezeBudget(freshFreeze.getBudgetPoolId(), freshFreeze.getBudgetAmount());
                    log.info("Budget unfrozen on auto-unfreeze: poolId={}, amount={}",
                            freshFreeze.getBudgetPoolId(), freshFreeze.getBudgetAmount());
                }

                // Re-read account AFTER update for accurate flow values
                PointsAccount account = pointsAccountMapper.selectByMemberId(freeze.getMemberId());
                long afterPoints = account != null ? account.getAvailablePoints() : 0;
                long beforePoints = afterPoints - freeze.getPoints();

                PointsFlow flow = PointsFlow.builder()
                        .memberId(freeze.getMemberId())
                        .eventId(eventId)
                        .eventType("UNFREEZE")
                        .pointsChange(freeze.getPoints())
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(freeze.getBizOrderNo())
                        .remark("自动解冻过期冻结积分，冻结单号: " + freeze.getFreezeNo())
                        .createTime(LocalDateTime.now())
                        .build();
                pointsFlowMapper.insert(flow);

                log.info("Auto-unfreeze: memberId={}, points={}, freezeNo={}",
                        freeze.getMemberId(), freeze.getPoints(), freeze.getFreezeNo());
                return true;
            });
            return result != null && result;
        } catch (DuplicateKeyException e) {
            log.info("Auto-unfreeze flow already inserted for freezeNo={}, skipping", freeze.getFreezeNo());
            return false;
        }
    }

    /**
     * Reset monthly earned counter: run on the first day of each month at 1 AM.
     * Resets the monthly_earned field for all accounts that haven't been reset yet.
     */
    @Scheduled(cron = "0 0 1 1 * ?")
    public void resetMonthlyEarned() {
        log.info("Starting monthly earned reset task");
        try {
            LocalDate today = LocalDate.now();
            int rows = pointsAccountMapper.resetMonthlyEarned(today);
            log.info("Monthly earned reset completed, accounts reset: {}", rows);
        } catch (Exception e) {
            log.error("Monthly earned reset task failed", e);
        }
    }
}
