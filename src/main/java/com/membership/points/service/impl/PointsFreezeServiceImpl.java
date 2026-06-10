package com.membership.points.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.membership.points.common.constant.RedisKeyConstants;
import com.membership.points.common.enums.FreezeStatusEnum;
import com.membership.points.common.enums.PointsSourceEnum;
import com.membership.points.common.enums.PointsTransactionTypeEnum;
import com.membership.points.common.exception.BusinessException;
import com.membership.points.common.exception.InsufficientPointsException;
import com.membership.points.common.result.Result;
import com.membership.points.common.util.RedisLockUtil;
import com.membership.points.common.util.SnowflakeIdGenerator;
import com.membership.points.dto.request.FreezePointsRequest;
import com.membership.points.dto.request.UnfreezePointsRequest;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.entity.PointsFreeze;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsFreezeMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.CacheService;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.PointsFreezeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分冻结服务实现
 */
@Slf4j
@Service
public class PointsFreezeServiceImpl implements PointsFreezeService {

    private final PointsAccountService pointsAccountService;
    private final PointsFreezeMapper pointsFreezeMapper;
    private final PointsBatchMapper pointsBatchMapper;
    private final PointsTransactionMapper pointsTransactionMapper;
    private final AuditLogService auditLogService;
    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;
    private final TransactionTemplate transactionTemplate;

    public PointsFreezeServiceImpl(PointsAccountService pointsAccountService,
                                   PointsFreezeMapper pointsFreezeMapper,
                                   PointsBatchMapper pointsBatchMapper,
                                   PointsTransactionMapper pointsTransactionMapper,
                                   AuditLogService auditLogService,
                                   CacheService cacheService,
                                   RedisLockUtil redisLockUtil,
                                   TransactionTemplate transactionTemplate) {
        this.pointsAccountService = pointsAccountService;
        this.pointsFreezeMapper = pointsFreezeMapper;
        this.pointsBatchMapper = pointsBatchMapper;
        this.pointsTransactionMapper = pointsTransactionMapper;
        this.auditLogService = auditLogService;
        this.cacheService = cacheService;
        this.redisLockUtil = redisLockUtil;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Result<String> freezePoints(FreezePointsRequest request) {
        Long memberId = request.getMemberId();
        String lockKey = RedisKeyConstants.LOCK_FREEZE + memberId;

        // 1. 获取Redis分布式锁，10秒超时
        boolean locked = redisLockUtil.tryLock(lockKey, 10000, 30000);
        if (!locked) {
            throw new BusinessException("获取冻结锁失败，请稍后重试");
        }

        try {
            // a. 加载账户，检查可用积分
            PointsAccount account = pointsAccountService.getOrCreateAccount(memberId);
            if (account.getAvailablePoints() < request.getPoints()) {
                throw new InsufficientPointsException("可用积分不足，当前可用: "
                        + account.getAvailablePoints() + ", 需要冻结: " + request.getPoints());
            }

            // b. 加载活跃批次（FIFO，按过期时间升序）
            List<PointsBatch> activeBatches = pointsBatchMapper.selectActiveBatchesByMemberId(memberId);

            // c. 迭代批次，分配冻结金额
            long remaining = request.getPoints();
            long totalFrozen = 0;
            List<Map<String, Long>> batchDetails = new ArrayList<>();

            for (PointsBatch batch : activeBatches) {
                if (remaining <= 0) {
                    break;
                }

                long canFreeze = batch.getRemainingPoints() - batch.getFrozenPoints();
                if (canFreeze <= 0) {
                    continue;
                }

                long freezeFromThis = Math.min(canFreeze, remaining);

                // 更新批次冻结积分
                batch.setFrozenPoints(batch.getFrozenPoints() + freezeFromThis);
                batch.setUpdatedAt(LocalDateTime.now());
                pointsBatchMapper.updateById(batch);

                // 记录冻结明细
                Map<String, Long> detail = new HashMap<>();
                detail.put("batchId", batch.getId());
                detail.put("frozenAmount", freezeFromThis);
                batchDetails.add(detail);

                totalFrozen += freezeFromThis;
                remaining -= freezeFromThis;
            }

            if (remaining > 0) {
                throw new InsufficientPointsException("可冻结积分不足，仍缺少: " + remaining);
            }

            // d. 事务处理
            final long finalTotalFrozen = totalFrozen;
            String freezeNo = transactionTemplate.execute(status -> {
                return doFreezeTransaction(memberId, finalTotalFrozen, batchDetails, account, request);
            });

            // e. 清除缓存，记录审计日志
            cacheService.evictPointsAccount(memberId);
            auditLogService.log("FREEZE", "POINTS", memberId, memberId, "SYSTEM",
                    "积分冻结: points=" + finalTotalFrozen + ", freezeNo=" + freezeNo);

            // f. 返回冻结流水号
            return Result.ok(freezeNo);

        } finally {
            // 3. 释放锁
            redisLockUtil.unlock(lockKey);
        }
    }

    private String doFreezeTransaction(Long memberId, long totalFrozen,
                                       List<Map<String, Long>> batchDetails,
                                       PointsAccount account,
                                       FreezePointsRequest request) {
        // 创建冻结记录
        String freezeNo = SnowflakeIdGenerator.generateTransactionNo("F");
        PointsFreeze freeze = new PointsFreeze();
        freeze.setFreezeNo(freezeNo);
        freeze.setMemberId(memberId);
        freeze.setFrozenPoints(totalFrozen);
        freeze.setStatus(FreezeStatusEnum.FROZEN.getCode());
        freeze.setRedemptionOrderId(request.getRedemptionOrderId());
        freeze.setFreezeDetail(JSONUtil.toJsonStr(batchDetails));
        freeze.setFrozenAt(LocalDateTime.now());
        freeze.setCreatedAt(LocalDateTime.now());
        freeze.setUpdatedAt(LocalDateTime.now());

        pointsFreezeMapper.insert(freeze);

        // 冻结账户积分
        pointsAccountService.freezePoints(memberId, totalFrozen, account.getVersion());

        // 插入冻结交易记录
        PointsTransaction transaction = new PointsTransaction();
        transaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("T"));
        transaction.setMemberId(memberId);
        transaction.setTransactionType(PointsTransactionTypeEnum.FREEZE.getCode());
        transaction.setSource(PointsSourceEnum.REDEMPTION.getCode());
        transaction.setPointsAmount(-totalFrozen);
        transaction.setBalanceBefore(account.getAvailablePoints());
        transaction.setBalanceAfter(account.getAvailablePoints() - totalFrozen);
        transaction.setFrozenBefore(account.getFrozenPoints());
        transaction.setFrozenAfter(account.getFrozenPoints() + totalFrozen);
        transaction.setRemark(request.getRemark());
        transaction.setCreatedAt(LocalDateTime.now());

        pointsTransactionMapper.insert(transaction);

        return freezeNo;
    }

    @Override
    public Result<?> unfreezePoints(UnfreezePointsRequest request) {
        // 1. 加载冻结记录，校验状态
        LambdaQueryWrapper<PointsFreeze> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PointsFreeze::getFreezeNo, request.getFreezeNo());
        PointsFreeze freeze = pointsFreezeMapper.selectOne(queryWrapper);

        if (freeze == null) {
            throw new BusinessException("冻结记录不存在: " + request.getFreezeNo());
        }
        if (!FreezeStatusEnum.FROZEN.getCode().equals(freeze.getStatus())) {
            throw new BusinessException("冻结记录状态异常，当前状态: " + freeze.getStatus());
        }

        // 2. 解析冻结明细
        List<Map> batchDetailList = JSONUtil.toList(freeze.getFreezeDetail(), Map.class);

        Long memberId = freeze.getMemberId();
        PointsAccount account = pointsAccountService.getOrCreateAccount(memberId);

        String action = request.getAction();

        if ("CONFIRM".equals(action)) {
            // 3. 确认扣减
            transactionTemplate.executeWithoutResult(status -> {
                doConfirmUnfreeze(freeze, batchDetailList, account, request);
            });
        } else if ("CANCEL".equals(action)) {
            // 4. 取消恢复
            transactionTemplate.executeWithoutResult(status -> {
                doCancelUnfreeze(freeze, batchDetailList, account, request);
            });
        } else {
            throw new BusinessException("不支持的解冻操作: " + action);
        }

        // 5. 清除缓存，记录审计日志
        cacheService.evictPointsAccount(memberId);
        auditLogService.log("UNFREEZE", "POINTS", memberId, memberId, "SYSTEM",
                "积分解冻: action=" + action + ", freezeNo=" + freeze.getFreezeNo()
                        + ", points=" + freeze.getFrozenPoints());

        return Result.ok();
    }

    private void doConfirmUnfreeze(PointsFreeze freeze, List<Map> batchDetailList,
                                   PointsAccount account, UnfreezePointsRequest request) {
        Long memberId = freeze.getMemberId();

        // 对每个批次扣减冻结积分和剩余积分
        for (Map batchDetail : batchDetailList) {
            Long batchId = ((Number) batchDetail.get("batchId")).longValue();
            Long frozenAmount = ((Number) batchDetail.get("frozenAmount")).longValue();

            PointsBatch batch = pointsBatchMapper.selectById(batchId);
            if (batch != null) {
                batch.setFrozenPoints(batch.getFrozenPoints() - frozenAmount);
                batch.setRemainingPoints(batch.getRemainingPoints() - frozenAmount);
                if (batch.getRemainingPoints() <= 0 && batch.getFrozenPoints() == 0) {
                    batch.setExpired(1);
                }
                batch.setUpdatedAt(LocalDateTime.now());
                pointsBatchMapper.updateById(batch);
            }
        }

        // 解冻并扣减账户积分
        pointsAccountService.unfreezeAndDeduct(memberId, freeze.getFrozenPoints(), account.getVersion());

        // 更新冻结记录状态
        freeze.setStatus(FreezeStatusEnum.UNFROZEN_SUCCESS.getCode());
        freeze.setUnfrozenAt(LocalDateTime.now());
        freeze.setUpdatedAt(LocalDateTime.now());
        pointsFreezeMapper.updateById(freeze);

        // 插入交易记录
        PointsTransaction transaction = new PointsTransaction();
        transaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("T"));
        transaction.setMemberId(memberId);
        transaction.setTransactionType(PointsTransactionTypeEnum.SPEND.getCode());
        transaction.setSource(PointsSourceEnum.REDEMPTION.getCode());
        transaction.setPointsAmount(-freeze.getFrozenPoints());
        transaction.setBalanceBefore(account.getAvailablePoints());
        transaction.setBalanceAfter(account.getAvailablePoints());
        transaction.setFrozenBefore(account.getFrozenPoints());
        transaction.setFrozenAfter(account.getFrozenPoints() - freeze.getFrozenPoints());
        transaction.setRemark(request.getRemark());
        transaction.setCreatedAt(LocalDateTime.now());

        pointsTransactionMapper.insert(transaction);
    }

    private void doCancelUnfreeze(PointsFreeze freeze, List<Map> batchDetailList,
                                  PointsAccount account, UnfreezePointsRequest request) {
        Long memberId = freeze.getMemberId();

        // 对每个批次恢复冻结积分
        for (Map batchDetail : batchDetailList) {
            Long batchId = ((Number) batchDetail.get("batchId")).longValue();
            Long frozenAmount = ((Number) batchDetail.get("frozenAmount")).longValue();

            PointsBatch batch = pointsBatchMapper.selectById(batchId);
            if (batch != null) {
                batch.setFrozenPoints(batch.getFrozenPoints() - frozenAmount);
                batch.setUpdatedAt(LocalDateTime.now());
                pointsBatchMapper.updateById(batch);
            }
        }

        // 解冻并恢复账户积分
        pointsAccountService.unfreezeAndRestore(memberId, freeze.getFrozenPoints(), account.getVersion());

        // 更新冻结记录状态
        freeze.setStatus(FreezeStatusEnum.UNFROZEN_FAIL.getCode());
        freeze.setUnfrozenAt(LocalDateTime.now());
        freeze.setUpdatedAt(LocalDateTime.now());
        pointsFreezeMapper.updateById(freeze);

        // 插入交易记录
        PointsTransaction transaction = new PointsTransaction();
        transaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("T"));
        transaction.setMemberId(memberId);
        transaction.setTransactionType(PointsTransactionTypeEnum.UNFREEZE.getCode());
        transaction.setSource(PointsSourceEnum.REDEMPTION.getCode());
        transaction.setPointsAmount(freeze.getFrozenPoints());
        transaction.setBalanceBefore(account.getAvailablePoints());
        transaction.setBalanceAfter(account.getAvailablePoints() + freeze.getFrozenPoints());
        transaction.setFrozenBefore(account.getFrozenPoints());
        transaction.setFrozenAfter(account.getFrozenPoints() - freeze.getFrozenPoints());
        transaction.setRemark(request.getRemark());
        transaction.setCreatedAt(LocalDateTime.now());

        pointsTransactionMapper.insert(transaction);
    }
}
