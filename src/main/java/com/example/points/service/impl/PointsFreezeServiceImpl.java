package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.FreezeRequest;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.entity.PointsFreeze;
import com.example.points.enums.FreezeStatus;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.BlacklistService;
import com.example.points.service.PointsFlowService;
import com.example.points.service.PointsFreezeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsFreezeServiceImpl implements PointsFreezeService {

    private final PointsAccountMapper pointsAccountMapper;
    private final PointsFreezeMapper pointsFreezeMapper;
    private final PointsFlowService pointsFlowService;
    private final BlacklistService blacklistService;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PointsFreeze freeze(FreezeRequest request) {
        // 1. Check blacklist
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单，无法冻结积分");
        }

        // 2. Check idempotent on freezeNo (fast path)
        LambdaQueryWrapper<PointsFreeze> freezeWrapper = new LambdaQueryWrapper<>();
        freezeWrapper.eq(PointsFreeze::getFreezeNo, request.getFreezeNo());
        List<PointsFreeze> existingFreezes = pointsFreezeMapper.selectList(freezeWrapper);
        if (!existingFreezes.isEmpty()) {
            log.info("Freeze already exists, freezeNo={}", request.getFreezeNo());
            return existingFreezes.get(0);
        }

        // 3. Redis lock on memberId
        String lockKey = "lock:freeze:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("获取锁失败，请稍后重试");
            }

            // 4. Transaction inside lock — commits before lock release
            return transactionTemplate.execute(status -> {
                // Re-check idempotent inside lock
                LambdaQueryWrapper<PointsFreeze> recheck = new LambdaQueryWrapper<>();
                recheck.eq(PointsFreeze::getFreezeNo, request.getFreezeNo());
                List<PointsFreeze> existing = pointsFreezeMapper.selectList(recheck);
                if (!existing.isEmpty()) {
                    return existing.get(0);
                }

                // Get account for before/after tracking
                LambdaQueryWrapper<PointsAccount> accountWrapper = new LambdaQueryWrapper<>();
                accountWrapper.eq(PointsAccount::getMemberId, request.getMemberId());
                PointsAccount account = pointsAccountMapper.selectOne(accountWrapper);
                if (account == null) {
                    throw new BusinessException("积分账户不存在");
                }

                long beforePoints = account.getAvailablePoints();

                // Freeze points
                int rows = pointsAccountMapper.freezePoints(request.getMemberId(), request.getPoints());
                if (rows == 0) {
                    throw new BusinessException("可用积分不足，无法冻结 " + request.getPoints() + " 积分");
                }

                long afterPoints = beforePoints - request.getPoints();

                // Create PointsFreeze record
                int freezeHours = request.getFreezeHours() != null ? request.getFreezeHours() : 72;
                PointsFreeze freeze = PointsFreeze.builder()
                        .memberId(request.getMemberId())
                        .freezeNo(request.getFreezeNo())
                        .points(request.getPoints())
                        .bizOrderNo(request.getBizOrderNo())
                        .reason(request.getReason())
                        .status(FreezeStatus.FROZEN.getCode())
                        .expireTime(LocalDateTime.now().plusHours(freezeHours))
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build();
                pointsFreezeMapper.insert(freeze);

                // Create PointsFlow with eventType=FREEZE
                PointsFlow flow = PointsFlow.builder()
                        .memberId(request.getMemberId())
                        .eventId("FREEZE_" + request.getFreezeNo())
                        .eventType("FREEZE")
                        .pointsChange(-request.getPoints())
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(request.getBizOrderNo())
                        .remark("冻结积分，冻结单号: " + request.getFreezeNo())
                        .createTime(LocalDateTime.now())
                        .build();
                pointsFlowService.saveFlow(flow);

                auditLogService.log("POINTS", "FREEZE", String.valueOf(request.getMemberId()), "MEMBER",
                        String.valueOf(beforePoints), String.valueOf(afterPoints), "SYSTEM", null);

                log.info("Points frozen: memberId={}, points={}, freezeNo={}",
                        request.getMemberId(), request.getPoints(), request.getFreezeNo());
                return freeze;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("冻结积分被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void unfreeze(String freezeNo) {
        // 1. Find freeze record, validate exists
        PointsFreeze freeze = getFreezeByNo(freezeNo);
        if (freeze.getStatus() != FreezeStatus.FROZEN.getCode()) {
            throw new BusinessException("冻结记录状态不是冻结中，无法解冻，当前状态: " + freeze.getStatus());
        }

        // 2. Redis lock on memberId
        String lockKey = "lock:freeze:" + freeze.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("获取锁失败，请稍后重试");
            }

            final Long memberId = freeze.getMemberId();
            final Long frozenPoints = freeze.getPoints();
            final String bizOrderNo = freeze.getBizOrderNo();

            // 3. Transaction inside lock
            transactionTemplate.executeWithoutResult(status -> {
                // CAS status transition: FROZEN → UNFROZEN
                int casRows = pointsFreezeMapper.updateStatusCAS(
                        freezeNo, FreezeStatus.FROZEN.getCode(), FreezeStatus.UNFROZEN.getCode());
                if (casRows == 0) {
                    log.info("Freeze already processed (CAS failed), freezeNo={}", freezeNo);
                    return;
                }

                LambdaQueryWrapper<PointsAccount> accountWrapper = new LambdaQueryWrapper<>();
                accountWrapper.eq(PointsAccount::getMemberId, memberId);
                PointsAccount account = pointsAccountMapper.selectOne(accountWrapper);
                long beforePoints = account.getAvailablePoints();

                // Unfreeze points
                int rows = pointsAccountMapper.unfreezePoints(memberId, frozenPoints);
                if (rows == 0) {
                    throw new BusinessException("解冻积分失败");
                }

                long afterPoints = beforePoints + frozenPoints;

                // Create PointsFlow with eventType=UNFREEZE
                PointsFlow flow = PointsFlow.builder()
                        .memberId(memberId)
                        .eventId("UNFREEZE_" + freezeNo)
                        .eventType("UNFREEZE")
                        .pointsChange(frozenPoints)
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(bizOrderNo)
                        .remark("解冻积分，冻结单号: " + freezeNo)
                        .createTime(LocalDateTime.now())
                        .build();
                pointsFlowService.saveFlow(flow);

                auditLogService.log("POINTS", "UNFREEZE", String.valueOf(memberId), "MEMBER",
                        String.valueOf(beforePoints), String.valueOf(afterPoints), "SYSTEM", null);

                log.info("Points unfrozen: memberId={}, points={}, freezeNo={}",
                        memberId, frozenPoints, freezeNo);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("解冻积分被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void settleFreeze(String freezeNo) {
        // 1. Find freeze record, validate exists
        PointsFreeze freeze = getFreezeByNo(freezeNo);
        if (freeze.getStatus() != FreezeStatus.FROZEN.getCode()) {
            throw new BusinessException("冻结记录状态不是冻结中，无法结算，当前状态: " + freeze.getStatus());
        }

        // 2. Redis lock on memberId
        String lockKey = "lock:freeze:" + freeze.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("获取锁失败，请稍后重试");
            }

            final Long memberId = freeze.getMemberId();
            final Long frozenPoints = freeze.getPoints();
            final String bizOrderNo = freeze.getBizOrderNo();

            // 3. Transaction inside lock
            transactionTemplate.executeWithoutResult(status -> {
                // CAS status transition: FROZEN → DEDUCTED
                int casRows = pointsFreezeMapper.updateStatusCAS(
                        freezeNo, FreezeStatus.FROZEN.getCode(), FreezeStatus.DEDUCTED.getCode());
                if (casRows == 0) {
                    log.info("Freeze already processed (CAS failed), freezeNo={}", freezeNo);
                    return;
                }

                LambdaQueryWrapper<PointsAccount> accountWrapper = new LambdaQueryWrapper<>();
                accountWrapper.eq(PointsAccount::getMemberId, memberId);
                PointsAccount account = pointsAccountMapper.selectOne(accountWrapper);
                long beforeFrozen = account.getFrozenPoints();

                // Deduct frozen points (order confirmed)
                int rows = pointsAccountMapper.deductFrozenPoints(memberId, frozenPoints);
                if (rows == 0) {
                    throw new BusinessException("扣减冻结积分失败");
                }

                long afterFrozen = beforeFrozen - frozenPoints;

                // Create PointsFlow with eventType=REDEEM
                PointsFlow flow = PointsFlow.builder()
                        .memberId(memberId)
                        .eventId("SETTLE_" + freezeNo)
                        .eventType("REDEEM")
                        .pointsChange(-frozenPoints)
                        .beforePoints(beforeFrozen)
                        .afterPoints(afterFrozen)
                        .bizOrderNo(bizOrderNo)
                        .remark("结算冻结积分，冻结单号: " + freezeNo)
                        .createTime(LocalDateTime.now())
                        .build();
                pointsFlowService.saveFlow(flow);

                auditLogService.log("POINTS", "SETTLE_FREEZE", String.valueOf(memberId), "MEMBER",
                        String.valueOf(beforeFrozen), String.valueOf(afterFrozen), "SYSTEM", null);

                log.info("Freeze settled: memberId={}, points={}, freezeNo={}",
                        memberId, frozenPoints, freezeNo);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("结算冻结积分被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private PointsFreeze getFreezeByNo(String freezeNo) {
        LambdaQueryWrapper<PointsFreeze> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsFreeze::getFreezeNo, freezeNo);
        PointsFreeze freeze = pointsFreezeMapper.selectOne(wrapper);
        if (freeze == null) {
            throw new BusinessException("冻结记录不存在，freezeNo=" + freezeNo);
        }
        return freeze;
    }
}
