package com.example.points.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.entity.RiskFreezeOrder;
import com.example.points.enums.ReviewStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.mapper.RiskFreezeOrderMapper;
import com.example.points.service.CircuitBreakerService;
import com.example.points.service.PointsFreezeService;
import com.example.points.service.AuditLogService;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.entity.PointsFreeze;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BudgetRiskTask {

    private final BudgetPoolMapper budgetPoolMapper;
    private final RiskFreezeOrderMapper riskFreezeOrderMapper;
    private final PointsFreezeMapper pointsFreezeMapper;
    private final CircuitBreakerService circuitBreakerService;
    private final PointsFreezeService pointsFreezeService;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * Reset daily budget counters: run at midnight daily.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void resetDailyBudgetCounters() {
        log.info("Starting daily budget counter reset task");

        String lockKey = "lock:budget:resetDaily:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 5, TimeUnit.MINUTES)) {
                log.info("Another instance is running resetDailyBudgetCounters, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring resetDailyBudgetCounters lock", e);
            return;
        }

        try {
            LocalDate today = LocalDate.now();
            int rows = budgetPoolMapper.resetDailyUsed(today);
            log.info("Daily budget counters reset completed, pools reset: {}", rows);
        } catch (Exception e) {
            log.error("Daily budget counter reset task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Reset monthly budget counters: run at 00:05 on the first day of each month.
     */
    @Scheduled(cron = "0 5 0 1 * ?")
    public void resetMonthlyBudgetCounters() {
        log.info("Starting monthly budget counter reset task");

        String lockKey = "lock:budget:resetMonthly:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 5, TimeUnit.MINUTES)) {
                log.info("Another instance is running resetMonthlyBudgetCounters, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring resetMonthlyBudgetCounters lock", e);
            return;
        }

        try {
            LocalDate today = LocalDate.now();
            int rows = budgetPoolMapper.resetMonthlyUsed(today);
            log.info("Monthly budget counters reset completed, pools reset: {}", rows);
        } catch (Exception e) {
            log.error("Monthly budget counter reset task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Circuit breaker recovery check: run every 5 minutes.
     * Transitions OPEN -> HALF_OPEN after cooldown, HALF_OPEN -> CLOSED after enough successes.
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void circuitBreakerRecoveryCheck() {
        log.info("Starting circuit breaker recovery check");

        String lockKey = "lock:budget:cbRecovery:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 5, TimeUnit.MINUTES)) {
                log.info("Another instance is running circuitBreakerRecoveryCheck, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring circuitBreakerRecoveryCheck lock", e);
            return;
        }

        try {
            circuitBreakerService.processRecoveryChecks();
            log.info("Circuit breaker recovery check completed");
        } catch (Exception e) {
            log.error("Circuit breaker recovery check failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Release expired risk freeze orders: run every 10 minutes.
     * Auto-approve PENDING orders past their expire_time.
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void releaseExpiredRiskFreezes() {
        log.info("Starting expired risk freeze release task");

        String lockKey = "lock:budget:releaseRiskFreeze:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 10, TimeUnit.MINUTES)) {
                log.info("Another instance is running releaseExpiredRiskFreezes, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring releaseExpiredRiskFreezes lock", e);
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LambdaQueryWrapper<RiskFreezeOrder> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(RiskFreezeOrder::getReviewStatus, ReviewStatus.PENDING.getCode())
                   .le(RiskFreezeOrder::getExpireTime, now);

            List<RiskFreezeOrder> expiredOrders = riskFreezeOrderMapper.selectList(wrapper);
            int totalProcessed = 0;

            for (RiskFreezeOrder order : expiredOrders) {
                try {
                    processExpiredRiskFreeze(order);
                    totalProcessed++;
                } catch (Exception e) {
                    log.error("Error processing expired risk freeze order: {}", order.getFreezeOrderNo(), e);
                }
            }

            log.info("Expired risk freeze release task completed, processed: {}", totalProcessed);
        } catch (Exception e) {
            log.error("Expired risk freeze release task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void processExpiredRiskFreeze(RiskFreezeOrder order) {
        // Re-check status (could have been processed by manual review)
        RiskFreezeOrder fresh = riskFreezeOrderMapper.selectById(order.getId());
        if (fresh == null || fresh.getReviewStatus() != ReviewStatus.PENDING.getCode()) {
            log.info("Risk freeze order already processed: {}", order.getFreezeOrderNo());
            return;
        }

        Boolean result = transactionTemplate.execute(status -> {
            // Look up freezeNo
            PointsFreeze freeze = pointsFreezeMapper.selectById(fresh.getPointsFreezeId());
            if (freeze == null) {
                log.warn("PointsFreeze record not found for risk freeze order: {}", fresh.getFreezeOrderNo());
                return false;
            }

            // Unfreeze (auto-approve = return points)
            try {
                pointsFreezeService.unfreeze(freeze.getFreezeNo());
            } catch (Exception e) {
                log.warn("Failed to unfreeze for expired risk order {}: {}",
                        fresh.getFreezeOrderNo(), e.getMessage());
                return false;
            }

            // Update order status
            fresh.setReviewStatus(ReviewStatus.APPROVED.getCode());
            fresh.setReviewer("SYSTEM");
            fresh.setReviewRemark("系统自动释放过期冻结工单");
            fresh.setReviewTime(LocalDateTime.now());
            fresh.setUpdateTime(LocalDateTime.now());
            riskFreezeOrderMapper.updateById(fresh);

            auditLogService.log("RISK_FREEZE", "AUTO_RELEASE", String.valueOf(fresh.getId()),
                    "RISK_FREEZE_ORDER", "PENDING", "APPROVED", "SYSTEM", null);

            log.info("Expired risk freeze order auto-released: {}", fresh.getFreezeOrderNo());
            return true;
        });
    }

    /**
     * Update budget pool status: run hourly.
     * Mark expired and exhausted pools.
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void updateBudgetPoolStatus() {
        log.info("Starting budget pool status update task");
        try {
            int expired = budgetPoolMapper.markExpiredPools();
            int exhausted = budgetPoolMapper.markExhaustedPools();
            log.info("Budget pool status update completed, expired: {}, exhausted: {}", expired, exhausted);
        } catch (Exception e) {
            log.error("Budget pool status update task failed", e);
        }
    }
}
