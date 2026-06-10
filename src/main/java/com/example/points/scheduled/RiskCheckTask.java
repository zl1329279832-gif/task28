package com.example.points.scheduled;

import com.example.points.mapper.PointsFlowMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.RiskControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskCheckTask {

    private final PointsFlowMapper pointsFlowMapper;
    private final RiskControlService riskControlService;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;

    /**
     * Periodic risk scan: scan for suspicious high-frequency activity.
     * Runs every 30 minutes.
     */
    @Scheduled(cron = "0 */30 * * * ?")
    public void periodicRiskScan() {
        log.info("Starting periodic risk scan");

        String lockKey = "lock:risk:scan:task";
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 30, TimeUnit.MINUTES)) {
                log.info("Another instance is running risk scan, skipping");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while acquiring risk scan lock", e);
            return;
        }

        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(30);

            // Find members with high frequency earn in the last 30 minutes
            // Using a simple approach: query recent flows and group by member
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.example.points.entity.PointsFlow> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            wrapper.gt(com.example.points.entity.PointsFlow::getPointsChange, 0)
                    .ge(com.example.points.entity.PointsFlow::getCreateTime, since)
                    .select(com.example.points.entity.PointsFlow::getMemberId);

            List<com.example.points.entity.PointsFlow> recentFlows = pointsFlowMapper.selectList(wrapper);

            // Group by memberId and find those with >= 10 events
            java.util.Map<Long, Long> memberCounts = recentFlows.stream()
                    .collect(Collectors.groupingBy(
                            com.example.points.entity.PointsFlow::getMemberId,
                            Collectors.counting()));

            int riskTriggered = 0;
            for (java.util.Map.Entry<Long, Long> entry : memberCounts.entrySet()) {
                if (entry.getValue() >= 10) {
                    try {
                        // Check if already flagged
                        if (riskControlService.detectHighFrequency(entry.getKey(), 30, 10)) {
                            String detail = String.format("定时扫描: 30分钟内积分发放%d次, memberId=%d",
                                    entry.getValue(), entry.getKey());
                            riskControlService.triggerRiskEvent(entry.getKey(),
                                    "HIGH_FREQUENCY", detail, null, null);
                            riskTriggered++;
                        }
                    } catch (Exception e) {
                        log.error("Error processing risk scan for memberId={}", entry.getKey(), e);
                    }
                }
            }

            log.info("Periodic risk scan completed, risks triggered: {}", riskTriggered);
        } catch (Exception e) {
            log.error("Periodic risk scan failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
