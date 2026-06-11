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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final CircuitBreakerService circuitBreakerService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsFlow processEvent(PointsEventRequest request) {
        // 1. Check blacklist
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单");
        }

        // 1.5 Budget pool validation
        if (request.getBudgetPoolId() != null) {
            if (!budgetPoolService.isPoolActiveAndValid(request.getBudgetPoolId())) {
                throw new BusinessException("预算池不存在、已暂停或已过期");
            }
        }

        // 1.6 Circuit breaker pre-check
        if (request.getBudgetPoolId() != null
                && !riskControlService.isCircuitBreakerAllowing(request.getBudgetPoolId())) {
            throw new BusinessException("熔断器已开启，积分发放被阻止");
        }

        // 2. Idempotent check before lock
        PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
        if (existingFlow != null) {
            log.info("Event already processed, eventId={}", request.getEventId());
            return existingFlow;
        }

        // 3. Acquire distributed lock
        String lockKey = "lock:points:event:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 4. Double-check idempotent inside lock
            existingFlow = flowService.checkIdempotent(request.getEventId());
            if (existingFlow != null) {
                return existingFlow;
            }

            // 5. Get or create account
            PointsAccount account = accountService.getOrCreateAccount(request.getMemberId());

            // 6. Calculate points via rule engine
            long points = ruleEngine.calculatePoints(request, account);
            if (points == 0) {
                log.info("No points calculated for eventId={}, memberId={}",
                        request.getEventId(), request.getMemberId());
                return null;
            }

            // 7. Reserve budget (before account update)
            if (request.getBudgetPoolId() != null && points > 0) {
                budgetPoolService.reserveBudget(request.getBudgetPoolId(), points);
            }

            // 8. Update account balance
            int rows = accountMapper.addPoints(request.getMemberId(), points);
            if (rows == 0) {
                throw new BusinessException("积分更新失败");
            }

            // Re-read account AFTER update for accurate flow values
            PointsAccount updatedAccount = accountMapper.selectByMemberId(request.getMemberId());
            long afterPoints = updatedAccount.getAvailablePoints();
            long beforePoints = afterPoints - points;

            // 8. Build and save flow record
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

            // 9.5 In-transaction risk check for BUDGET_EXHAUSTION
            //     Must happen inside the transaction so we can rollback if breached
            if (request.getBudgetPoolId() != null && points > 0) {
                java.util.List<String> breachedTypes = riskControlService.evaluateInTransaction(
                        request.getMemberId(), request.getBudgetPoolId(), flow.getId(), points);

                if (breachedTypes.contains("BUDGET_EXHAUSTION")) {
                    budgetPoolService.releaseBudget(request.getBudgetPoolId(), points);
                    circuitBreakerService.recordTrip(request.getBudgetPoolId(), true);
                    throw new BusinessException("预算池已耗尽，积分发放被拒绝");
                }
            }

            // 10. Audit log
            auditLogService.log("POINTS", "EARN", String.valueOf(request.getMemberId()),
                    "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                    "SYSTEM", null);

            // 11. Post-commit risk evaluation (for non-BUDGET_EXHAUSTION rules)
            final Long budgetPoolId = request.getBudgetPoolId();
            final long earnedPoints = points;
            if (budgetPoolId != null
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                try {
                                    riskControlService.evaluatePostIssuance(
                                            request.getMemberId(), budgetPoolId,
                                            flow.getId(), earnedPoints);
                                } catch (Exception e) {
                                    log.error("Post-issuance risk evaluation failed, memberId={}, poolId={}",
                                            request.getMemberId(), budgetPoolId, e);
                                }
                            }
                        });
            }

            log.info("Points event processed: memberId={}, eventType={}, points={}, eventId={}",
                    request.getMemberId(), request.getEventType(), points, request.getEventId());
            return flow;
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
    public PointsFlow adjust(AdjustRequest request) {
        // 1. Acquire distributed lock FIRST
        String lockKey = "lock:points:adjust:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 2. Idempotent check INSIDE lock (double-check pattern)
            PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
            if (existingFlow != null) {
                log.info("Adjust event already processed, eventId={}", request.getEventId());
                return existingFlow;
            }

            // 3. Get account
            PointsAccount account = accountService.getAccount(request.getMemberId());

            long points = request.getPoints();
            long absPoints = Math.abs(points);

            // 4. Add or deduct points
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

            // 5. Re-read account AFTER SQL for accurate flow values
            PointsAccount updatedAccount = accountMapper.selectByMemberId(request.getMemberId());
            long afterPoints = updatedAccount.getAvailablePoints();
            long beforePoints = afterPoints - points; // reverse-compute (works for both + and -)

            // 6. Build and save flow
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

            // 7. Audit log
            auditLogService.log("POINTS", "ADJUST", String.valueOf(request.getMemberId()),
                    "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                    request.getOperator(), "");

            log.info("Points adjusted: memberId={}, points={}, eventId={}",
                    request.getMemberId(), points, request.getEventId());
            return flow;
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
    public PointsFlow refund(RefundRequest request) {
        // 1. Idempotent check
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
            // Partial refund: refundAmount is the number of points to return
            // Cap at original points to prevent over-refund
            refundPoints = Math.min(request.getRefundAmount(), originalPoints);
        } else {
            // Full refund
            refundPoints = originalPoints;
        }
        if (refundPoints <= 0) {
            throw new BusinessException("退款积分计算结果为0");
        }

        // 3. Acquire lock
        String lockKey = "lock:points:event:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // Re-check idempotent
            existingFlow = flowService.checkIdempotent(request.getEventId());
            if (existingFlow != null) {
                return existingFlow;
            }

            PointsAccount account = accountService.getAccount(request.getMemberId());

            // 4. Return points to account
            int rows = accountMapper.addPoints(request.getMemberId(), refundPoints);
            if (rows == 0) {
                throw new BusinessException("退款积分添加失败");
            }

            // Re-read account AFTER update for accurate flow values
            PointsAccount updatedAccount = accountMapper.selectByMemberId(request.getMemberId());
            long afterPoints = updatedAccount.getAvailablePoints();
            long beforePoints = afterPoints - refundPoints;

            // 5. Build and save refund flow
            PointsFlow flow = PointsFlow.builder()
                    .memberId(request.getMemberId())
                    .eventId(request.getEventId())
                    .eventType("REFUND")
                    .pointsChange(refundPoints)
                    .beforePoints(beforePoints)
                    .afterPoints(afterPoints)
                    .bizOrderNo(request.getBizOrderNo())
                    .remark("退款: " + request.getBizOrderNo())
                    .createTime(LocalDateTime.now())
                    .build();
            flowService.saveFlow(flow);

            auditLogService.log("POINTS", "REFUND", String.valueOf(request.getMemberId()),
                    "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                    request.getOperator() != null ? request.getOperator() : "SYSTEM", null);

            // Restore budget to original pool
            if (originalFlow.getBudgetPoolId() != null) {
                if (!budgetPoolService.isPoolActiveAndValid(originalFlow.getBudgetPoolId())) {
                    log.warn("Restoring budget to inactive pool: poolId={}, flowId={}",
                            originalFlow.getBudgetPoolId(), originalFlow.getId());
                }
                budgetPoolService.restoreBudget(originalFlow.getBudgetPoolId(), refundPoints);
            }

            log.info("Points refunded: memberId={}, refundPoints={}, bizOrderNo={}",
                    request.getMemberId(), refundPoints, request.getBizOrderNo());
            return flow;
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
