package com.example.points.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.entity.BudgetPool;
import com.example.points.enums.BudgetPoolStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BudgetPoolTask {

    private final BudgetPoolMapper budgetPoolMapper;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;

    /**
     * Check and trigger circuit break for pools that exceed their threshold.
     * Runs every 5 minutes.
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void checkAndCircuitBreak() {
        log.info("Starting budget pool circuit break check");

        String lockKey = "lock:budget:circuit-break:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 5, TimeUnit.MINUTES)) {
                log.info("Another instance is running circuit break check, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring circuit break lock", e);
            return;
        }

        try {
            LambdaQueryWrapper<BudgetPool> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(BudgetPool::getStatus, BudgetPoolStatus.ENABLED.getCode());
            List<BudgetPool> pools = budgetPoolMapper.selectList(wrapper);

            int triggered = 0;
            for (BudgetPool pool : pools) {
                try {
                    if (pool.getTotalBudget() > 0) {
                        long usedPercent = pool.getUsedBudget() * 100 / pool.getTotalBudget();
                        if (usedPercent >= pool.getCircuitBreakRate()) {
                            budgetPoolMapper.updateStatus(pool.getId(),
                                    BudgetPoolStatus.CIRCUIT_BROKEN.getCode());

                            auditLogService.log("BUDGET_POOL", "CIRCUIT_BREAK",
                                    String.valueOf(pool.getId()), "BUDGET_POOL",
                                    String.valueOf(usedPercent) + "%",
                                    "使用率达到熔断阈值" + pool.getCircuitBreakRate() + "%",
                                    "SYSTEM", null);

                            log.warn("Budget pool circuit broken: poolId={}, usedPercent={}%, threshold={}%",
                                    pool.getId(), usedPercent, pool.getCircuitBreakRate());
                            triggered++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Error checking circuit break for poolId={}", pool.getId(), e);
                }
            }

            log.info("Circuit break check completed, triggered: {}", triggered);
        } catch (Exception e) {
            log.error("Circuit break check task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Release expired pools: disable pools past their effective_end time.
     * Runs every hour.
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void releaseExpiredPools() {
        log.info("Starting expired pool release task");

        String lockKey = "lock:budget:expire:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 30, TimeUnit.MINUTES)) {
                log.info("Another instance is running expired pool release, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring expired pool lock", e);
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LambdaQueryWrapper<BudgetPool> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(BudgetPool::getStatus, BudgetPoolStatus.ENABLED.getCode())
                    .isNotNull(BudgetPool::getEffectiveEnd)
                    .lt(BudgetPool::getEffectiveEnd, now);

            List<BudgetPool> expiredPools = budgetPoolMapper.selectList(wrapper);

            int processed = 0;
            for (BudgetPool pool : expiredPools) {
                try {
                    budgetPoolMapper.updateStatus(pool.getId(), BudgetPoolStatus.DISABLED.getCode());

                    auditLogService.log("BUDGET_POOL", "EXPIRE", String.valueOf(pool.getId()),
                            "BUDGET_POOL", "ENABLED", "DISABLED", "SYSTEM", null);

                    log.info("Expired pool disabled: poolId={}, activityCode={}",
                            pool.getId(), pool.getActivityCode());
                    processed++;
                } catch (Exception e) {
                    log.error("Error disabling expired pool: poolId={}", pool.getId(), e);
                }
            }

            log.info("Expired pool release completed, processed: {}", processed);
        } catch (Exception e) {
            log.error("Expired pool release task failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
