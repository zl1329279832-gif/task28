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
import com.example.points.service.PointsAccountService;
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
    private final PointsAccountService accountService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PointsFreeze freeze(FreezeRequest request) {
        // 1. Fast-fail blacklist check
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单，无法冻结积分");
        }

        // 2. Fast-fail idempotent check on freezeNo
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

            // 4. Execute within transaction INSIDE lock
            return transactionTemplate.execute(status -> {
                // Re-check idempotent inside lock+transaction
                List<PointsFreeze> existing = pointsFreezeMapper.selectList(freezeWrapper);
                if (!existing.isEmpty()) {
                    return existing.get(0);
                }

                // Re-check blacklist inside lock
                if (blacklistService.isBlacklisted(request.getMemberId())) {
                    throw new BusinessException("会员已被加入黑名单，无法冻结积分");
                }

                // Get account for existence check
                PointsAccount account = accountService.getAccount(request.getMemberId());

                // Freeze points
                int rows = pointsAccountMapper.freezePoints(request.getMemberId(), request.getPoints());
                if (rows == 0) {
                    throw new BusinessException("可用积分不足，无法冻结 " + request.getPoints() + " 积分");
                }

                // Re-read account AFTER freezePoints for accurate flow values
                PointsAccount updatedAccount = pointsAccountMapper.selectByMemberId(request.getMemberId());
                long afterPoints = updatedAccount.getAvailablePoints();
                long beforePoints = afterPoints + request.getPoints();

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
        // 1. Find freeze record, check status == FROZEN
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

            // 3. Execute within transaction INSIDE lock
            transactionTemplate.execute(status -> {
                // Re-check status inside lock+transaction
                PointsFreeze freshFreeze = getFreezeByNo(freezeNo);
                if (freshFreeze.getStatus() != FreezeStatus.FROZEN.getCode()) {
                    log.info("Freeze already processed, freezeNo={}, status={}", freezeNo, freshFreeze.getStatus());
                    return null;
                }

                // Unfreeze points
                int rows = pointsAccountMapper.unfreezePoints(freshFreeze.getMemberId(), freshFreeze.getPoints());
                if (rows == 0) {
                    throw new BusinessException("解冻积分失败");
                }

                // Re-read account AFTER unfreezePoints for accurate flow values
                PointsAccount updatedAccount = pointsAccountMapper.selectByMemberId(freshFreeze.getMemberId());
                long afterPoints = updatedAccount.getAvailablePoints();
                long beforePoints = afterPoints - freshFreeze.getPoints();

                // Update freeze status to UNFROZEN
                freshFreeze.setStatus(FreezeStatus.UNFROZEN.getCode());
                freshFreeze.setUpdateTime(LocalDateTime.now());
                pointsFreezeMapper.updateById(freshFreeze);

                // Create PointsFlow with eventType=UNFREEZE
                PointsFlow flow = PointsFlow.builder()
                        .memberId(freshFreeze.getMemberId())
                        .eventId("UNFREEZE_" + freezeNo)
                        .eventType("UNFREEZE")
                        .pointsChange(freshFreeze.getPoints())
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(freshFreeze.getBizOrderNo())
                        .remark("解冻积分，冻结单号: " + freezeNo)
                        .createTime(LocalDateTime.now())
                        .build();
                pointsFlowService.saveFlow(flow);

                auditLogService.log("POINTS", "UNFREEZE", String.valueOf(freshFreeze.getMemberId()), "MEMBER",
                        String.valueOf(beforePoints), String.valueOf(afterPoints), "SYSTEM", null);

                log.info("Points unfrozen: memberId={}, points={}, freezeNo={}",
                        freshFreeze.getMemberId(), freshFreeze.getPoints(), freezeNo);
                return null;
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
        // 1. Find freeze record, check status == FROZEN
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

            // 3. Execute within transaction INSIDE lock
            transactionTemplate.execute(status -> {
                // Re-check status inside lock+transaction
                PointsFreeze freshFreeze = getFreezeByNo(freezeNo);
                if (freshFreeze.getStatus() != FreezeStatus.FROZEN.getCode()) {
                    log.info("Freeze already processed, freezeNo={}, status={}", freezeNo, freshFreeze.getStatus());
                    return null;
                }

                // Deduct frozen points (order confirmed)
                int rows = pointsAccountMapper.deductFrozenPoints(freshFreeze.getMemberId(), freshFreeze.getPoints());
                if (rows == 0) {
                    throw new BusinessException("扣减冻结积分失败");
                }

                // Re-read account AFTER deductFrozenPoints for accurate flow values
                PointsAccount updatedAccount = pointsAccountMapper.selectByMemberId(freshFreeze.getMemberId());
                long afterFrozen = updatedAccount.getFrozenPoints();
                long beforeFrozen = afterFrozen + freshFreeze.getPoints();

                // Update freeze status to DEDUCTED
                freshFreeze.setStatus(FreezeStatus.DEDUCTED.getCode());
                freshFreeze.setUpdateTime(LocalDateTime.now());
                pointsFreezeMapper.updateById(freshFreeze);

                // Create PointsFlow with eventType=REDEEM
                PointsFlow flow = PointsFlow.builder()
                        .memberId(freshFreeze.getMemberId())
                        .eventId("SETTLE_" + freezeNo)
                        .eventType("REDEEM")
                        .pointsChange(-freshFreeze.getPoints())
                        .beforePoints(beforeFrozen)
                        .afterPoints(afterFrozen)
                        .bizOrderNo(freshFreeze.getBizOrderNo())
                        .remark("结算冻结积分，冻结单号: " + freezeNo)
                        .createTime(LocalDateTime.now())
                        .build();
                pointsFlowService.saveFlow(flow);

                auditLogService.log("POINTS", "SETTLE_FREEZE", String.valueOf(freshFreeze.getMemberId()), "MEMBER",
                        String.valueOf(beforeFrozen), String.valueOf(afterFrozen), "SYSTEM", null);

                log.info("Freeze settled: memberId={}, points={}, freezeNo={}",
                        freshFreeze.getMemberId(), freshFreeze.getPoints(), freezeNo);
                return null;
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
