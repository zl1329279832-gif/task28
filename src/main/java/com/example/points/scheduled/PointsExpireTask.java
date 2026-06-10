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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointsExpireTask {

    private final PointsAccountMapper pointsAccountMapper;
    private final PointsFlowMapper pointsFlowMapper;
    private final PointsFreezeMapper pointsFreezeMapper;
    private final AuditLogService auditLogService;

    /**
     * Expire points: run at 2 AM daily.
     * Processes credit flows (REGISTER, PURCHASE, CHECKIN, ACTIVITY, REFUND)
     * that have expired within the last 24 hours.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void expirePoints() {
        log.info("Starting points expiration task");
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
                        // Sum the expired points
                        long sumExpired = memberFlows.stream()
                                .mapToLong(PointsFlow::getPointsChange)
                                .sum();

                        // Try to expire points from account
                        int rows = pointsAccountMapper.expirePoints(memberId, sumExpired);
                        if (rows > 0) {
                            // Create EXPIRE flow record
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
        }
    }

    /**
     * Auto-unfreeze expired freezes: run every 10 minutes.
     * Finds frozen records past their expiration time and unfreezes the points.
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void autoUnfreezeExpired() {
        log.info("Starting auto-unfreeze expired task");
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
                    // Unfreeze points
                    int rows = pointsAccountMapper.unfreezePoints(freeze.getMemberId(), freeze.getPoints());
                    if (rows > 0) {
                        // Update freeze status to EXPIRED(3)
                        freeze.setStatus(FreezeStatus.EXPIRED.getCode());
                        freeze.setUpdateTime(LocalDateTime.now());
                        pointsFreezeMapper.updateById(freeze);

                        // Create UNFREEZE flow
                        LambdaQueryWrapper<PointsAccount> accountWrapper = new LambdaQueryWrapper<>();
                        accountWrapper.eq(PointsAccount::getMemberId, freeze.getMemberId());
                        PointsAccount account = pointsAccountMapper.selectOne(accountWrapper);
                        long afterPoints = account != null ? account.getAvailablePoints() : 0;

                        PointsFlow flow = PointsFlow.builder()
                                .memberId(freeze.getMemberId())
                                .eventId("AUTO_UNFREEZE_" + freeze.getFreezeNo())
                                .eventType("UNFREEZE")
                                .pointsChange(freeze.getPoints())
                                .beforePoints(afterPoints - freeze.getPoints())
                                .afterPoints(afterPoints)
                                .bizOrderNo(freeze.getBizOrderNo())
                                .remark("自动解冻过期冻结积分，冻结单号: " + freeze.getFreezeNo())
                                .createTime(LocalDateTime.now())
                                .build();
                        pointsFlowMapper.insert(flow);

                        log.info("Auto-unfreeze: memberId={}, points={}, freezeNo={}",
                                freeze.getMemberId(), freeze.getPoints(), freeze.getFreezeNo());
                        totalProcessed++;
                    }
                } catch (Exception e) {
                    log.error("Error auto-unfreezing freezeNo={}", freeze.getFreezeNo(), e);
                }
            }

            log.info("Auto-unfreeze task completed, total processed: {}", totalProcessed);
        } catch (Exception e) {
            log.error("Auto-unfreeze task failed", e);
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
