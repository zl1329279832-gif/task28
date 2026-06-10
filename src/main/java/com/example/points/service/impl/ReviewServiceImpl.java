package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.ReviewRequest;
import com.example.points.entity.ReviewOrder;
import com.example.points.entity.RiskEvent;
import com.example.points.enums.ReviewResult;
import com.example.points.enums.RiskEventStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.mapper.ReviewOrderMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewOrderMapper reviewOrderMapper;
    private final RiskEventMapper riskEventMapper;
    private final PointsFreezeService freezeService;
    private final BlacklistService blacklistService;
    private final BudgetPoolMapper budgetPoolMapper;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;

    @Override
    public List<ReviewOrder> listPendingReviews() {
        LambdaQueryWrapper<ReviewOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewOrder::getStatus, 0)
               .orderByAsc(ReviewOrder::getCreateTime);
        return reviewOrderMapper.selectList(wrapper);
    }

    @Override
    public ReviewOrder getReview(String reviewNo) {
        LambdaQueryWrapper<ReviewOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewOrder::getReviewNo, reviewNo);
        ReviewOrder order = reviewOrderMapper.selectOne(wrapper);
        if (order == null) {
            throw new BusinessException("复核单不存在");
        }
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(ReviewRequest request) {
        String lockKey = "lock:review:" + request.getReviewNo();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 1. Find review order
            LambdaQueryWrapper<ReviewOrder> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ReviewOrder::getReviewNo, request.getReviewNo());
            ReviewOrder order = reviewOrderMapper.selectOne(wrapper);
            if (order == null) {
                throw new BusinessException("复核单不存在");
            }
            if (order.getStatus() == 1) {
                throw new BusinessException("复核单已处理");
            }

            // 2. Find related risk event
            RiskEvent riskEvent = riskEventMapper.selectById(order.getRiskEventId());
            if (riskEvent == null) {
                throw new BusinessException("关联风控事件不存在");
            }

            String actionTaken;

            if (request.getReviewResult().equals(ReviewResult.APPROVED.getCode())) {
                // 3a. Approved: unfreeze points
                if (riskEvent.getRelatedFreezeNo() != null) {
                    try {
                        freezeService.unfreeze(riskEvent.getRelatedFreezeNo());
                    } catch (Exception e) {
                        log.warn("Unfreeze failed during review approval: {}", e.getMessage());
                    }
                }
                // Release frozen budget if applicable
                if (riskEvent.getPoolId() != null) {
                    budgetPoolMapper.releaseFrozenBudget(riskEvent.getPoolId(), 0L);
                }
                riskEvent.setStatus(RiskEventStatus.APPROVED.getCode());
                actionTaken = "UNFREEZE";
            } else {
                // 3b. Rejected: deduct frozen points
                if (riskEvent.getRelatedFreezeNo() != null) {
                    try {
                        freezeService.settleFreeze(riskEvent.getRelatedFreezeNo());
                    } catch (Exception e) {
                        log.warn("Settle freeze failed during review rejection: {}", e.getMessage());
                    }
                }
                // Add to blacklist if requested
                if (Boolean.TRUE.equals(request.getAddBlacklist())) {
                    try {
                        blacklistService.addToBlacklist(riskEvent.getMemberId(),
                                "风控复核拒绝: " + request.getReviewComment(), request.getReviewer(), null);
                    } catch (Exception e) {
                        log.warn("Add blacklist failed during review rejection: {}", e.getMessage());
                    }
                    actionTaken = "DEDUCT+ADD_BLACKLIST";
                } else {
                    actionTaken = "DEDUCT";
                }
                riskEvent.setStatus(RiskEventStatus.REJECTED.getCode());
            }

            // 4. Update risk event
            riskEvent.setUpdateTime(LocalDateTime.now());
            riskEventMapper.updateById(riskEvent);

            // 5. Update review order
            order.setReviewResult(request.getReviewResult());
            order.setReviewer(request.getReviewer());
            order.setReviewComment(request.getReviewComment());
            order.setActionTaken(actionTaken);
            order.setReviewTime(LocalDateTime.now());
            order.setStatus(1);
            order.setUpdateTime(LocalDateTime.now());
            reviewOrderMapper.updateById(order);

            auditLogService.log("REVIEW", "EXECUTE", String.valueOf(order.getId()),
                    "REVIEW_ORDER", String.valueOf(request.getReviewResult()), actionTaken,
                    request.getReviewer(), null);

            log.info("Review completed: reviewNo={}, result={}, action={}",
                    request.getReviewNo(), request.getReviewResult(), actionTaken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
