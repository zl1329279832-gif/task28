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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsFlow processEvent(PointsEventRequest request) {
        // 1. Check blacklist
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单");
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

            long beforePoints = account.getAvailablePoints();

            // 7. Update account balance
            int rows = accountMapper.addPoints(request.getMemberId(), points);
            if (rows == 0) {
                throw new BusinessException("积分更新失败");
            }

            long afterPoints = beforePoints + points;

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
                    .createTime(LocalDateTime.now())
                    .build();
            flowService.saveFlow(flow);

            // 9. Audit log
            auditLogService.log("POINTS", "EARN", String.valueOf(request.getMemberId()),
                    "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                    "SYSTEM", null);

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
        // 1. Idempotent check
        PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
        if (existingFlow != null) {
            log.info("Adjust event already processed, eventId={}", request.getEventId());
            return existingFlow;
        }

        // 2. Get account
        PointsAccount account = accountService.getAccount(request.getMemberId());
        if (account == null) {
            throw new BusinessException("会员积分账户不存在");
        }

        long points = request.getPoints();
        long absPoints = Math.abs(points);
        long beforePoints = account.getAvailablePoints();
        long afterPoints;

        // 3. Add or deduct points
        if (points > 0) {
            int rows = accountMapper.addPoints(request.getMemberId(), absPoints);
            if (rows == 0) {
                throw new BusinessException("积分调整失败");
            }
            afterPoints = beforePoints + points;
        } else if (points < 0) {
            if (beforePoints < absPoints) {
                throw new BusinessException("可用积分不足");
            }
            int rows = accountMapper.deductPoints(request.getMemberId(), absPoints);
            if (rows == 0) {
                throw new BusinessException("可用积分不足");
            }
            afterPoints = beforePoints - absPoints;
        } else {
            afterPoints = beforePoints;
        }

        // 4. Build and save flow
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

        // 5. Audit log
        auditLogService.log("POINTS", "ADJUST", String.valueOf(request.getMemberId()),
                "MEMBER", String.valueOf(beforePoints), String.valueOf(afterPoints),
                request.getOperator(), "");

        log.info("Points adjusted: memberId={}, points={}, eventId={}",
                request.getMemberId(), points, request.getEventId());
        return flow;
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
            long beforePoints = account.getAvailablePoints();

            // 4. Return points to account
            int rows = accountMapper.addPoints(request.getMemberId(), refundPoints);
            if (rows == 0) {
                throw new BusinessException("退款积分添加失败");
            }

            long afterPoints = beforePoints + refundPoints;

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
