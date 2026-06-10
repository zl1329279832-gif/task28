package com.membership.points.job;

import com.membership.points.service.PointsExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 积分过期定时任务
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PointsExpirationJob {

    private final PointsExpirationService pointsExpirationService;

    @Scheduled(cron = "${points.expiration.cron:0 0 2 * * ?}")
    public void executeExpiration() {
        log.info("积分过期任务开始执行");
        long start = System.currentTimeMillis();
        try {
            int count = pointsExpirationService.expireBatch();
            log.info("积分过期任务完成，处理{}个批次，耗时{}ms", count, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("积分过期任务异常", e);
        }
    }
}
