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
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsFreeze freeze(FreezeRequest request) {
        // 1. Check blacklist
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单，无法冻结积分");
        }

        // 2. Check idempotent on freezeNo
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

            // Re-check idempotent
            existingFreezes = pointsFreezeMapper.selectList(freezeWrapper);
            if (!existingFreezes.isEmpty()) {
                return existingFreezes.get(0);
            }

            // Get account for existence check
            PointsAccount account = accountService.getAccount(request.getMemberId());

            // 4. Freeze points
            int rows = pointsAccountMapper.freezePoints(request.getMemberId(), request.getPoints());
            if (rows == 0) {
                throw new BusinessException("可用积分不足，无法冻结 " + request.getPoints() + " 积分");
            }

            // Re-read account AFTER freezePoints for accurate flow values
            PointsAccount updatedAccount = pointsAccountMapper.selectByMemberId(request.getMemberId());
            long afterPoints = updatedAccount.getAvailablePoints();
            long beforePoints = afterPoints + request.getPoints();

            // 5. Create PointsFreeze record
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

            // 6. Create PointsFlow with eventType=FREEZE
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
    @Transactional(rollbackFor = Exception.class)
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

            // Re-check status
            freeze = getFreezeByNo(freezeNo);
            if (freeze.getStatus() != FreezeStatus.FROZEN.getCode()) {
                log.info("Freeze already processed, freezeNo={}, status={}", freezeNo, freeze.getStatus());
                return;
            }

            // 3. Unfreeze points
            int rows = pointsAccountMapper.unfreezePoints(freeze.getMemberId(), freeze.getPoints());
            if (rows == 0) {
                throw new BusinessException("解冻积分失败");
            }

            // Re-read account AFTER unfreezePoints for accurate flow values
            PointsAccount updatedAccount = pointsAccountMapper.selectByMemberId(freeze.getMemberId());
            long afterPoints = updatedAccount.getAvailablePoints();
            long beforePoints = afterPoints - freeze.getPoints();

            // 4. Update freeze status to UNFROZEN
            freeze.setStatus(FreezeStatus.UNFROZEN.getCode());
            freeze.setUpdateTime(LocalDateTime.now());
            pointsFreezeMapper.updateById(freeze);

            // 5. Create PointsFlow with eventType=UNFREEZE
            PointsFlow flow = PointsFlow.builder()
                    .memberId(freeze.getMemberId())
                    .eventId("UNFREEZE_" + freezeNo)
                    .eventType("UNFREEZE")
                    .pointsChange(freeze.getPoints())
                    .beforePoints(beforePoints)
                    .afterPoints(afterPoints)
                    .bizOrderNo(freeze.getBizOrderNo())
                    .remark("解冻积分，冻结单号: " + freezeNo)
                    .createTime(LocalDateTime.now())
                    .build();
            pointsFlowService.saveFlow(flow);

            auditLogService.log("POINTS", "UNFREEZE", String.valueOf(freeze.getMemberId()), "MEMBER",
                    String.valueOf(beforePoints), String.valueOf(afterPoints), "SYSTEM", null);

            log.info("Points unfrozen: memberId={}, points={}, freezeNo={}",
                    freeze.getMemberId(), freeze.getPoints(), freezeNo);
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
    @Transactional(rollbackFor = Exception.class)
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

            // Re-check status
            freeze = getFreezeByNo(freezeNo);
            if (freeze.getStatus() != FreezeStatus.FROZEN.getCode()) {
                log.info("Freeze already processed, freezeNo={}, status={}", freezeNo, freeze.getStatus());
                return;
            }

            // 3. Deduct frozen points (order confirmed)
            int rows = pointsAccountMapper.deductFrozenPoints(freeze.getMemberId(), freeze.getPoints());
            if (rows == 0) {
                throw new BusinessException("扣减冻结积分失败");
            }

            // Re-read account AFTER deductFrozenPoints for accurate flow values
            PointsAccount updatedAccount = pointsAccountMapper.selectByMemberId(freeze.getMemberId());
            long afterFrozen = updatedAccount.getFrozenPoints();
            long beforeFrozen = afterFrozen + freeze.getPoints();

            // 4. Update freeze status to DEDUCTED
            freeze.setStatus(FreezeStatus.DEDUCTED.getCode());
            freeze.setUpdateTime(LocalDateTime.now());
            pointsFreezeMapper.updateById(freeze);

            // 5. Create PointsFlow with eventType=REDEEM
            PointsFlow flow = PointsFlow.builder()
                    .memberId(freeze.getMemberId())
                    .eventId("SETTLE_" + freezeNo)
                    .eventType("REDEEM")
                    .pointsChange(-freeze.getPoints())
                    .beforePoints(beforeFrozen)
                    .afterPoints(afterFrozen)
                    .bizOrderNo(freeze.getBizOrderNo())
                    .remark("结算冻结积分，冻结单号: " + freezeNo)
                    .createTime(LocalDateTime.now())
                    .build();
            pointsFlowService.saveFlow(flow);

            auditLogService.log("POINTS", "SETTLE_FREEZE", String.valueOf(freeze.getMemberId()), "MEMBER",
                    String.valueOf(beforeFrozen), String.valueOf(afterFrozen), "SYSTEM", null);

            log.info("Freeze settled: memberId={}, points={}, freezeNo={}",
                    freeze.getMemberId(), freeze.getPoints(), freezeNo);
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
