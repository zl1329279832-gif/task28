package com.membership.points.service.impl;

import com.membership.points.common.constant.PointsConstants;
import com.membership.points.common.enums.PointsSourceEnum;
import com.membership.points.common.enums.PointsTransactionTypeEnum;
import com.membership.points.common.exception.InsufficientPointsException;
import com.membership.points.common.result.Result;
import com.membership.points.common.util.SnowflakeIdGenerator;
import com.membership.points.dto.request.AdjustPointsRequest;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.CacheService;
import com.membership.points.service.IdempotentService;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.PointsAdjustService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分调整服务实现
 */
@Slf4j
@Service
public class PointsAdjustServiceImpl implements PointsAdjustService {

    private final IdempotentService idempotentService;
    private final PointsAccountService pointsAccountService;
    private final PointsTransactionMapper pointsTransactionMapper;
    private final PointsBatchMapper pointsBatchMapper;
    private final AuditLogService auditLogService;
    private final CacheService cacheService;
    private final TransactionTemplate transactionTemplate;

    public PointsAdjustServiceImpl(IdempotentService idempotentService,
                                   PointsAccountService pointsAccountService,
                                   PointsTransactionMapper pointsTransactionMapper,
                                   PointsBatchMapper pointsBatchMapper,
                                   AuditLogService auditLogService,
                                   CacheService cacheService,
                                   TransactionTemplate transactionTemplate) {
        this.idempotentService = idempotentService;
        this.pointsAccountService = pointsAccountService;
        this.pointsTransactionMapper = pointsTransactionMapper;
        this.pointsBatchMapper = pointsBatchMapper;
        this.auditLogService = auditLogService;
        this.cacheService = cacheService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Result<?> adjustPoints(AdjustPointsRequest request) {
        // 1. 幂等检查
        boolean isNew = idempotentService.checkAndMark(request.getIdempotentKey(), "ADJUST");
        if (!isNew) {
            String cachedResult = idempotentService.getCachedResult(request.getIdempotentKey());
            return Result.ok(cachedResult);
        }

        Long memberId = request.getMemberId();
        Long points = request.getPoints();

        // 2. 加载账户
        PointsAccount account = pointsAccountService.getOrCreateAccount(memberId);

        // 3. 判断调整类型
        boolean isAdd = points > 0;
        PointsTransactionTypeEnum transactionType = isAdd
                ? PointsTransactionTypeEnum.ADJUST_ADD
                : PointsTransactionTypeEnum.ADJUST_DEDUCT;

        long absPoints = Math.abs(points);

        // 4. 调减时检查可用积分是否足够
        if (!isAdd) {
            if (account.getAvailablePoints() < absPoints) {
                throw new InsufficientPointsException("可用积分不足，当前可用: "
                        + account.getAvailablePoints() + ", 需要调减: " + absPoints);
            }
        }

        // 5. 执行事务
        transactionTemplate.executeWithoutResult(status -> {
            doAdjustTransaction(memberId, absPoints, isAdd, transactionType, account, request);
        });

        // 6. 记录审计日志（包含操作人信息）
        auditLogService.log("ADJUST", "POINTS", memberId, memberId, request.getOperator(),
                "积分调整: type=" + transactionType.getCode() + ", points=" + points
                        + ", reason=" + request.getReason());

        // 7. 清除缓存
        cacheService.evictPointsAccount(memberId);

        // 8. 标记幂等结果
        idempotentService.markResult(request.getIdempotentKey(), "SUCCESS");

        return Result.ok();
    }

    private void doAdjustTransaction(Long memberId, long absPoints, boolean isAdd,
                                     PointsTransactionTypeEnum transactionType,
                                     PointsAccount account, AdjustPointsRequest request) {
        // a. 构建交易记录
        PointsTransaction transaction = new PointsTransaction();
        transaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("A"));
        transaction.setMemberId(memberId);
        transaction.setTransactionType(transactionType.getCode());
        transaction.setSource(PointsSourceEnum.ADMIN.getCode());
        transaction.setPointsAmount(isAdd ? absPoints : -absPoints);
        transaction.setBalanceBefore(account.getAvailablePoints());
        transaction.setBalanceAfter(isAdd
                ? account.getAvailablePoints() + absPoints
                : account.getAvailablePoints() - absPoints);
        transaction.setFrozenBefore(account.getFrozenPoints());
        transaction.setFrozenAfter(account.getFrozenPoints());
        transaction.setOperator(request.getOperator());
        transaction.setRemark(request.getReason());
        transaction.setCreatedAt(LocalDateTime.now());

        // b. 插入交易记录
        pointsTransactionMapper.insert(transaction);

        if (isAdd) {
            // c. 调增：创建积分批次（12个月过期），增加积分
            PointsBatch batch = new PointsBatch();
            batch.setMemberId(memberId);
            batch.setSource(PointsSourceEnum.ADMIN.getCode());
            batch.setOriginalPoints(absPoints);
            batch.setRemainingPoints(absPoints);
            batch.setFrozenPoints(0L);
            batch.setEarnTransactionId(transaction.getId());
            batch.setEarnedAt(LocalDateTime.now());
            batch.setExpireAt(LocalDateTime.now().plusMonths(PointsConstants.DEFAULT_EXPIRE_MONTHS));
            batch.setExpired(0);
            batch.setCreatedAt(LocalDateTime.now());
            batch.setUpdatedAt(LocalDateTime.now());

            pointsBatchMapper.insert(batch);

            // 回填batchId
            transaction.setBatchId(batch.getId());
            pointsTransactionMapper.updateById(transaction);

            pointsAccountService.addPoints(memberId, absPoints, account.getVersion());
        } else {
            // d. 调减：FIFO扣减积分批次
            deductFromBatchesFifo(memberId, absPoints);

            pointsAccountService.deductPoints(memberId, absPoints, account.getVersion());
        }
    }

    /**
     * FIFO方式从积分批次中扣减积分
     *
     * @param memberId  会员ID
     * @param absPoints 需要扣减的积分（正数）
     */
    private void deductFromBatchesFifo(Long memberId, long absPoints) {
        List<PointsBatch> activeBatches = pointsBatchMapper.selectActiveBatchesByMemberId(memberId);
        long remaining = absPoints;

        for (PointsBatch batch : activeBatches) {
            if (remaining <= 0) {
                break;
            }

            long canDeduct = batch.getRemainingPoints() - batch.getFrozenPoints();
            if (canDeduct <= 0) {
                continue;
            }

            long deductFromThis = Math.min(canDeduct, remaining);
            batch.setRemainingPoints(batch.getRemainingPoints() - deductFromThis);
            if (batch.getRemainingPoints() <= 0 && batch.getFrozenPoints() == 0) {
                batch.setExpired(1);
            }
            batch.setUpdatedAt(LocalDateTime.now());
            pointsBatchMapper.updateById(batch);

            remaining -= deductFromThis;
        }

        if (remaining > 0) {
            log.warn("积分调减FIFO扣减不完全, memberId: {}, 剩余未扣减: {}", memberId, remaining);
        }
    }
}
