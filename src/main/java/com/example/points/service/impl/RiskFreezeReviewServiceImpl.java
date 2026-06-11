package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.FreezeRequest;
import com.example.points.dto.ReviewDecisionRequest;
import com.example.points.entity.PointsFreeze;
import com.example.points.entity.RiskEvent;
import com.example.points.entity.RiskFreezeOrder;
import com.example.points.enums.ReviewStatus;
import com.example.points.enums.RiskEventStatus;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.mapper.RiskFreezeOrderMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.PointsFreezeService;
import com.example.points.service.RiskFreezeReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiskFreezeReviewServiceImpl implements RiskFreezeReviewService {

    private final RiskFreezeOrderMapper riskFreezeOrderMapper;
    private final RiskEventMapper riskEventMapper;
    private final PointsFreezeMapper pointsFreezeMapper;
    private final PointsFreezeService pointsFreezeService;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RiskFreezeOrder createRiskFreeze(Long memberId, Long poolId, Long riskEventId,
                                              String freezeType, Long points, int expireHours) {
        // Create a freeze via the existing freeze service
        String freezeNo = "RISK_" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
        FreezeRequest freezeRequest = new FreezeRequest();
        freezeRequest.setMemberId(memberId);
        freezeRequest.setPoints(points);
        freezeRequest.setFreezeNo(freezeNo);
        freezeRequest.setBizOrderNo("RISK_" + freezeNo);
        freezeRequest.setReason("风控冻结: " + freezeType);
        freezeRequest.setFreezeHours(expireHours);

        PointsFreeze pointsFreeze;
        if (poolId != null) {
            pointsFreeze = pointsFreezeService.freezeWithBudget(freezeRequest, poolId);
        } else {
            pointsFreeze = pointsFreezeService.freeze(freezeRequest);
        }

        // Create risk freeze order
        String freezeOrderNo = "RFO_" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
        RiskFreezeOrder order = RiskFreezeOrder.builder()
                .freezeOrderNo(freezeOrderNo)
                .poolId(poolId)
                .memberId(memberId)
                .pointsFreezeId(pointsFreeze.getId())
                .riskEventId(riskEventId)
                .freezeType(freezeType)
                .points(points)
                .reviewStatus(ReviewStatus.PENDING.getCode())
                .expireTime(LocalDateTime.now().plusHours(expireHours))
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        riskFreezeOrderMapper.insert(order);

        auditLogService.log("RISK_FREEZE", "CREATE", String.valueOf(order.getId()),
                "RISK_FREEZE_ORDER", null, String.valueOf(points),
                "SYSTEM", null);

        log.info("Risk freeze order created: orderNo={}, memberId={}, points={}, freezeNo={}",
                freezeOrderNo, memberId, points, freezeNo);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long freezeOrderId, ReviewDecisionRequest request) {
        String lockKey = "lock:risk:review:" + freezeOrderId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            RiskFreezeOrder order = getReview(freezeOrderId);
            if (order.getReviewStatus() != ReviewStatus.PENDING.getCode()) {
                throw new BusinessException("工单已处理，当前状态: " + order.getReviewStatus());
            }

            // Look up freezeNo from points_freeze table
            String freezeNo = getFreezeNoById(order.getPointsFreezeId());

            // Unfreeze points (approve = return points to member)
            pointsFreezeService.unfreeze(freezeNo);

            // Update order status
            order.setReviewStatus(ReviewStatus.APPROVED.getCode());
            order.setReviewer(request.getReviewer());
            order.setReviewRemark(request.getRemark());
            order.setReviewTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            riskFreezeOrderMapper.updateById(order);

            // Resolve risk event
            if (order.getRiskEventId() != null) {
                RiskEvent event = riskEventMapper.selectById(order.getRiskEventId());
                if (event != null) {
                    event.setStatus(RiskEventStatus.RESOLVED.getCode());
                    riskEventMapper.updateById(event);
                }
            }

            auditLogService.log("RISK_FREEZE", "APPROVE", String.valueOf(freezeOrderId),
                    "RISK_FREEZE_ORDER", "PENDING", "APPROVED",
                    request.getReviewer(), null);

            auditLogService.log("RISK_FREEZE", "APPROVE_CHAIN", String.valueOf(freezeOrderId),
                    "RISK_FREEZE_ORDER",
                    String.format("{\"freezeOrderNo\":\"%s\",\"pointsFreezeId\":%d,\"poolId\":%s}",
                            order.getFreezeOrderNo(), order.getPointsFreezeId(), order.getPoolId()),
                    "APPROVED",
                    request.getReviewer(), null);

            log.info("Risk freeze order approved: id={}, reviewer={}", freezeOrderId, request.getReviewer());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long freezeOrderId, ReviewDecisionRequest request) {
        String lockKey = "lock:risk:review:" + freezeOrderId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            RiskFreezeOrder order = getReview(freezeOrderId);
            if (order.getReviewStatus() != ReviewStatus.PENDING.getCode()) {
                throw new BusinessException("工单已处理，当前状态: " + order.getReviewStatus());
            }

            // Look up freezeNo from points_freeze table
            String freezeNo = getFreezeNoById(order.getPointsFreezeId());

            // Settle (deduct) frozen points
            pointsFreezeService.settleFreeze(freezeNo);

            // Update order status
            order.setReviewStatus(ReviewStatus.REJECTED.getCode());
            order.setReviewer(request.getReviewer());
            order.setReviewRemark(request.getRemark());
            order.setReviewTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            riskFreezeOrderMapper.updateById(order);

            auditLogService.log("RISK_FREEZE", "REJECT", String.valueOf(freezeOrderId),
                    "RISK_FREEZE_ORDER", "PENDING", "REJECTED",
                    request.getReviewer(), null);

            auditLogService.log("RISK_FREEZE", "REJECT_CHAIN", String.valueOf(freezeOrderId),
                    "RISK_FREEZE_ORDER",
                    String.format("{\"freezeOrderNo\":\"%s\",\"pointsFreezeId\":%d,\"poolId\":%s}",
                            order.getFreezeOrderNo(), order.getPointsFreezeId(), order.getPoolId()),
                    "REJECTED",
                    request.getReviewer(), null);

            log.info("Risk freeze order rejected: id={}, reviewer={}", freezeOrderId, request.getReviewer());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public List<RiskFreezeOrder> listPendingReviews() {
        LambdaQueryWrapper<RiskFreezeOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RiskFreezeOrder::getReviewStatus, ReviewStatus.PENDING.getCode());
        return riskFreezeOrderMapper.selectList(wrapper);
    }

    @Override
    public RiskFreezeOrder getReview(Long freezeOrderId) {
        RiskFreezeOrder order = riskFreezeOrderMapper.selectById(freezeOrderId);
        if (order == null) {
            throw new BusinessException("风控冻结工单不存在: " + freezeOrderId);
        }
        return order;
    }

    private String getFreezeNoById(Long pointsFreezeId) {
        PointsFreeze freeze = pointsFreezeMapper.selectById(pointsFreezeId);
        if (freeze == null) {
            throw new BusinessException("积分冻结记录不存在: " + pointsFreezeId);
        }
        return freeze.getFreezeNo();
    }
}
