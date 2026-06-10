package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.membership.points.common.enums.PointsSourceEnum;
import com.membership.points.common.enums.PointsTransactionTypeEnum;
import com.membership.points.common.util.SnowflakeIdGenerator;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.CacheService;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.PointsExpirationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 积分过期服务实现
 */
@Slf4j
@Service
public class PointsExpirationServiceImpl implements PointsExpirationService {

    private final PointsBatchMapper pointsBatchMapper;
    private final PointsAccountService pointsAccountService;
    private final PointsTransactionMapper pointsTransactionMapper;
    private final AuditLogService auditLogService;
    private final CacheService cacheService;

    @Value("${points.expiration.batch-size:500}")
    private int batchSize;

    public PointsExpirationServiceImpl(PointsBatchMapper pointsBatchMapper,
                                       PointsAccountService pointsAccountService,
                                       PointsTransactionMapper pointsTransactionMapper,
                                       AuditLogService auditLogService,
                                       CacheService cacheService) {
        this.pointsBatchMapper = pointsBatchMapper;
        this.pointsAccountService = pointsAccountService;
        this.pointsTransactionMapper = pointsTransactionMapper;
        this.auditLogService = auditLogService;
        this.cacheService = cacheService;
    }

    @Override
    public int expireBatch() {
        // 1. 查询过期批次（分页）
        LambdaQueryWrapper<PointsBatch> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PointsBatch::getExpired, 0)
                .gt(PointsBatch::getRemainingPoints, 0)
                .le(PointsBatch::getExpireAt, LocalDateTime.now())
                .orderByAsc(PointsBatch::getExpireAt)
                .last("LIMIT " + batchSize);

        List<PointsBatch> expiredBatches = pointsBatchMapper.selectList(queryWrapper);

        if (expiredBatches.isEmpty()) {
            log.info("没有需要过期处理的积分批次");
            return 0;
        }

        int totalExpired = 0;
        Set<Long> affectedMemberIds = new HashSet<>();

        // 2. 逐个批次处理，每个批次独立事务（故障隔离）
        for (PointsBatch batch : expiredBatches) {
            try {
                boolean success = processExpireBatch(batch.getId());
                if (success) {
                    totalExpired++;
                    affectedMemberIds.add(batch.getMemberId());
                }
            } catch (Exception e) {
                log.error("过期处理失败, batchId: {}, memberId: {}", batch.getId(), batch.getMemberId(), e);
                // 故障隔离：继续处理下一个批次
            }
        }

        // 3. 清除所有受影响会员的缓存
        for (Long memberId : affectedMemberIds) {
            try {
                cacheService.evictPointsAccount(memberId);
            } catch (Exception e) {
                log.error("清除缓存失败, memberId: {}", memberId, e);
            }
        }

        // 4. 返回过期处理的总数
        log.info("积分过期处理完成, 共处理: {}, 成功: {}", expiredBatches.size(), totalExpired);
        return totalExpired;
    }

    /**
     * 处理单个过期批次（独立事务）
     *
     * @param batchId 批次ID
     * @return 是否成功处理
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean processExpireBatch(Long batchId) {
        // a. 重新加载批次（获取最新状态）
        PointsBatch batch = pointsBatchMapper.selectById(batchId);
        if (batch == null || batch.getExpired() == 1) {
            return false;
        }

        // b. 计算可过期积分（排除冻结部分）
        long expiringPoints = batch.getRemainingPoints() - batch.getFrozenPoints();
        if (expiringPoints <= 0) {
            return false;
        }

        Long memberId = batch.getMemberId();

        // c. 加载账户
        PointsAccount account = pointsAccountService.getOrCreateAccount(memberId);

        // d. 更新批次：减少剩余积分，判断是否标记为已过期
        batch.setRemainingPoints(batch.getRemainingPoints() - expiringPoints);
        if (batch.getRemainingPoints() <= batch.getFrozenPoints() && batch.getFrozenPoints() == 0) {
            batch.setExpired(1);
        }
        batch.setUpdatedAt(LocalDateTime.now());
        pointsBatchMapper.updateById(batch);

        // e. 过期账户积分
        pointsAccountService.expirePoints(memberId, expiringPoints, account.getVersion());

        // f. 插入过期交易记录
        PointsTransaction transaction = new PointsTransaction();
        transaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("X"));
        transaction.setMemberId(memberId);
        transaction.setTransactionType(PointsTransactionTypeEnum.EXPIRE.getCode());
        transaction.setSource(PointsSourceEnum.EXPIRATION.getCode());
        transaction.setPointsAmount(-expiringPoints);
        transaction.setBalanceBefore(account.getAvailablePoints());
        transaction.setBalanceAfter(account.getAvailablePoints() - expiringPoints);
        transaction.setFrozenBefore(account.getFrozenPoints());
        transaction.setFrozenAfter(account.getFrozenPoints());
        transaction.setBatchId(batchId);
        transaction.setRemark("积分过期自动处理");
        transaction.setCreatedAt(LocalDateTime.now());

        pointsTransactionMapper.insert(transaction);

        // g. 记录审计日志
        auditLogService.log("EXPIRE", "POINTS_BATCH", batchId, memberId, "SYSTEM",
                "积分批次过期: batchId=" + batchId + ", expiringPoints=" + expiringPoints);

        return true;
    }
}
