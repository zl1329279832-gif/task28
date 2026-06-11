package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.AdjustRequest;
import com.example.points.dto.PointsEventRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsEventServiceImpl implements PointsEventService {

    private final PointsAccountService accountService;
    private final PointsFlowService flowService;
    private final RuleEngine ruleEngine;
    private final PointsAccountMapper accountMapper;
    private final PointsFlowMapper flowMapper;
    private final BlacklistService blacklistService;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;
    private final BudgetPoolService budgetPoolService;
    private final RiskControlService riskControlService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PointsFlow processEvent(PointsEventRequest request) {
        // 1. Fast-fail pre-checks (outside lock, optimistic)
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单");
        }

        if (request.getBudgetPoolId() != null
                && !riskControlService.isCircuitBreakerAllowing(request.getBudgetPoolId())) {
            throw new BusinessException("熔断器已开启，积分发放被阻止");
        }

        PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
        if (existingFlow != null) {
            log.info("Event already processed, eventId={}", request.getEventId());
            return existingFlow;
        }

        // 2. Acquire distributed lock
        String lockKey = "lock:points:event:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 3. Execute within transaction INSIDE lock (commit before unlock)
            PointsFlow result = transactionTemplate.execute(status -> {
                // Double-check idempotent inside lock+transaction
                PointsFlow existing = flowService.checkIdempotent(request.getEventId());
                if (existing != null) {
                    return existing;
                }

                // Double-check blacklist inside lock (race: added between pre-check and lock)
                if (blacklistService.isBlacklisted(request.getMemberId())) {
                    throw new BusinessException("会员已被加入黑名单");
                }

                // Double-check circuit breaker inside lock
                if (request.getBudgetPoolId() != null
                        && !riskControlService.isCircuitBreakerAllowing(request.getBudgetPoolId())) {
                    throw new BusinessException("熔断器已开启，积分发放被阻止");
                }

                // Get or create account
                PointsAccount account = accountService.getOrCreateAccount(request.getMemberId());

                // Calculate points via rule engine
                long points = ruleEngine.calculatePoints(request, account);
                if (points == 0) {
                    log.info("No points calculated for eventId={}, memberId={}",
                            request.getEventId(), request.getMemberId());
                    return null;
                }

                // Reserve budget (before account update)
                if (request.getBudgetPoolId() != null && points > 0) {
                    budgetPoolService.reserveBudget(request.getBudgetPoolId(), points);
                }

                // Update account balance
                int rows = accountMapper.addPoints(request.getMemberId(), points);
                if (rows == 0) {
                    throw new BusinessException("积分更新失败");
                }

                // Re-read account AFTER update for accurate flow values
                PointsAccount updatedAccount = accountMapper.selectByMemberId(request.getMemberId());
                long afterPoints = updatedAccount.getAvailablePoints();
                long beforePoints = afterPoints - points;

                // Build and save flow record
                PointsFlow flow = PointsFlow.builder()
                        .memberId(request.getMemberId())
                        .eventId(request.getEventId())
                        .eventType(request.getEventType())
                        .pointsChange(points)
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(request.getBizOrderNo())
                        .expireTime(LocalDateTime.now().plusMonths(12))
                        .remark(request.getRemark())
                        .budgetPoolId(request.getBudgetPoolId())
                        .createTime(LocalDateTime.now())
                        .build();
                flowService.saveFlow(flow);

                // Audit log
                auditLogService.log("POINTS", "EARN", String.valueOf(request.getMemberId()),
                        "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                        "SYSTEM", null);

                log.info("Points event processed: memberId={}, eventType={}, points={}, eventId={}",
                        request.getMemberId(), request.getEventType(), points, request.getEventId());
                return flow;
            });

            // 4. Post-commit risk evaluation (transaction committed, still holding lock)
            if (result != null && request.getBudgetPoolId() != null) {
                try {
                    riskControlService.evaluatePostIssuance(
                            request.getMemberId(), request.getBudgetPoolId(),
                            result.getId(), result.getPointsChange());
                } catch (Exception e) {
                    log.error("Post-issuance risk evaluation failed, memberId={}, poolId={}",
                            request.getMemberId(), request.getBudgetPoolId(), e);
                }
            }

            return result;
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
    public PointsFlow adjust(AdjustRequest request) {
        // 1. Acquire distributed lock FIRST
        String lockKey = "lock:points:adjust:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 2. Execute within transaction INSIDE lock
            return transactionTemplate.execute(status -> {
                // Idempotent check INSIDE lock+transaction
                PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
                if (existingFlow != null) {
                    log.info("Adjust event already processed, eventId={}", request.getEventId());
                    return existingFlow;
                }

                // Get account
                PointsAccount account = accountService.getAccount(request.getMemberId());

                long points = request.getPoints();
                long absPoints = Math.abs(points);

                // Add or deduct points
                if (points > 0) {
                    int rows = accountMapper.addPoints(request.getMemberId(), absPoints);
                    if (rows == 0) {
                        throw new BusinessException("积分调整失败");
                    }
                } else if (points < 0) {
                    int rows = accountMapper.deductPoints(request.getMemberId(), absPoints);
                    if (rows == 0) {
                        throw new BusinessException("可用积分不足");
                    }
                }

                // Re-read account AFTER SQL for accurate flow values
                PointsAccount updatedAccount = accountMapper.selectByMemberId(request.getMemberId());
                long afterPoints = updatedAccount.getAvailablePoints();
                long beforePoints = afterPoints - points;

                // Build and save flow
                PointsFlow flow = PointsFlow.builder()
                        .memberId(request.getMemberId())
                        .eventId(request.getEventId())
                        .eventType("ADJUST")
                        .pointsChange(points)
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .remark(request.getReason())
                        .createTime(LocalDateTime.now())
                        .build();
                flowService.saveFlow(flow);

                // Audit log
                auditLogService.log("POINTS", "ADJUST", String.valueOf(request.getMemberId()),
                        "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                        request.getOperator(), "");

                log.info("Points adjusted: memberId={}, points={}, eventId={}",
                        request.getMemberId(), points, request.getEventId());
                return flow;
            });
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
    public PointsFlow refund(RefundRequest request) {
        // 1. Pre-check idempotent (fast-fail)
        PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
        if (existingFlow != null) {
            log.info("Refund event already processed, eventId={}", request.getEventId());
            return existingFlow;
        }

        // 2. Find original PURCHASE flow by bizOrderNo
        LambdaQueryWrapper<PointsFlow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsFlow::getBizOrderNo, request.getBizOrderNo())
               .eq(PointsFlow::getMemberId, request.getMemberId())
               .eq(PointsFlow::getEventType, "PURCHASE")
               .orderByDesc(PointsFlow::getCreateTime)
               .last("LIMIT 1");
        PointsFlow originalFlow = flowMapper.selectOne(wrapper);
        if (originalFlow == null) {
            throw new BusinessException("原始消费积分流水不存在");
        }

        // 3. Calculate refund points (full or partial)
        long originalPoints = originalFlow.getPointsChange();
        long refundPoints;
        if (request.getRefundAmount() != null && request.getRefundAmount() > 0) {
            refundPoints = Math.min(request.getRefundAmount(), originalPoints);
        } else {
            refundPoints = originalPoints;
        }
        if (refundPoints <= 0) {
            throw new BusinessException("退款积分计算结果为0");
        }

        // 4. Acquire lock
        String lockKey = "lock:points:event:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            final long finalRefundPoints = refundPoints;
            // 5. Execute within transaction INSIDE lock
            return transactionTemplate.execute(status -> {
                // Re-check idempotent inside lock+transaction
                PointsFlow existing = flowService.checkIdempotent(request.getEventId());
                if (existing != null) {
                    return existing;
                }

                PointsAccount account = accountService.getAccount(request.getMemberId());

                // Return points to account
                int rows = accountMapper.addPoints(request.getMemberId(), finalRefundPoints);
                if (rows == 0) {
                    throw new BusinessException("退款积分添加失败");
                }

                // Restore budget to original pool (use releaseBudget to restore daily/monthly counters)
                if (originalFlow.getBudgetPoolId() != null) {
                    budgetPoolService.releaseBudget(originalFlow.getBudgetPoolId(), finalRefundPoints);
                }

                // Re-read account AFTER update for accurate flow values
                PointsAccount updatedAccount = accountMapper.selectByMemberId(request.getMemberId());
                long afterPoints = updatedAccount.getAvailablePoints();
                long beforePoints = afterPoints - finalRefundPoints;

                // Build and save refund flow (with budgetPoolId for audit traceability)
                PointsFlow flow = PointsFlow.builder()
                        .memberId(request.getMemberId())
                        .eventId(request.getEventId())
                        .eventType("REFUND")
                        .pointsChange(finalRefundPoints)
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(request.getBizOrderNo())
                        .budgetPoolId(originalFlow.getBudgetPoolId())
                        .remark("退款: " + request.getBizOrderNo())
                        .createTime(LocalDateTime.now())
                        .build();
                flowService.saveFlow(flow);

                auditLogService.log("POINTS", "REFUND", String.valueOf(request.getMemberId()),
                        "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                        request.getOperator() != null ? request.getOperator() : "SYSTEM", null);

                log.info("Points refunded: memberId={}, refundPoints={}, bizOrderNo={}",
                        request.getMemberId(), finalRefundPoints, request.getBizOrderNo());
                return flow;
            });
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
