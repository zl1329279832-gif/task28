package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.membership.points.common.constant.PointsConstants;
import com.membership.points.common.enums.PointsSourceEnum;
import com.membership.points.common.enums.PointsTransactionTypeEnum;
import com.membership.points.common.enums.RedemptionStatusEnum;
import com.membership.points.common.exception.BusinessException;
import com.membership.points.common.result.Result;
import com.membership.points.common.util.SnowflakeIdGenerator;
import com.membership.points.dto.request.RefundRequest;
import com.membership.points.entity.BenefitInventory;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.entity.RedemptionOrder;
import com.membership.points.mapper.BenefitInventoryMapper;
import com.membership.points.mapper.BenefitMapper;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.mapper.RedemptionOrderMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.BlacklistService;
import com.membership.points.service.CacheService;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 退款服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private final RedemptionOrderMapper redemptionOrderMapper;
    private final BenefitMapper benefitMapper;
    private final BenefitInventoryMapper benefitInventoryMapper;
    private final PointsAccountService pointsAccountService;
    private final PointsBatchMapper pointsBatchMapper;
    private final PointsTransactionMapper pointsTransactionMapper;
    private final BlacklistService blacklistService;
    private final AuditLogService auditLogService;
    private final CacheService cacheService;

    @Value("${points.refund.max-days:30}")
    private int maxRefundDays;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> refundOrder(Long orderId, RefundRequest request) {
        // ========== 1. LOAD AND VALIDATE ORDER ==========
        RedemptionOrder order = redemptionOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!RedemptionStatusEnum.COMPLETED.getCode().equals(order.getStatus())) {
            throw new BusinessException("订单状态不允许退款");
        }

        // ========== 2. CHECK REFUND TIME WINDOW ==========
        if (order.getCompletedAt() != null
                && order.getCompletedAt().isBefore(LocalDateTime.now().minusDays(maxRefundDays))) {
            throw new BusinessException("超过退款期限");
        }

        // ========== 3. CHECK BLACKLIST ==========
        boolean isBlacklisted = blacklistService.isBlockedForEarn(order.getMemberId());

        // ========== 4. TRANSACTIONAL OPERATIONS ==========

        // a. UPDATE REDEMPTION ORDER
        order.setStatus(RedemptionStatusEnum.REFUNDED.getCode());
        order.setRefundReason(request.getReason());
        order.setRefundedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        redemptionOrderMapper.updateById(order);

        // b. RETURN STOCK
        BenefitInventory inventory = benefitInventoryMapper.selectById(order.getInventoryId());
        if (inventory != null) {
            inventory.setAvailableStock(inventory.getAvailableStock() + order.getQuantity());
            inventory.setUpdatedAt(LocalDateTime.now());
            benefitInventoryMapper.updateById(inventory);
        }

        // c. RETURN POINTS (only if not blacklisted)
        if (!isBlacklisted) {
            long returnedPoints = order.getPointsCost();

            // Load the original SPEND transaction for reference
            LambdaQueryWrapper<PointsTransaction> txWrapper = new LambdaQueryWrapper<>();
            txWrapper.eq(PointsTransaction::getReferenceId, order.getOrderNo())
                    .eq(PointsTransaction::getSource, PointsSourceEnum.REDEMPTION.getCode())
                    .eq(PointsTransaction::getTransactionType, PointsTransactionTypeEnum.SPEND.getCode());
            PointsTransaction originalTransaction = pointsTransactionMapper.selectOne(txWrapper);
            if (originalTransaction != null) {
                log.info("找到原始消费交易, transactionNo={}, pointsAmount={}",
                        originalTransaction.getTransactionNo(), originalTransaction.getPointsAmount());
            }

            // Load account
            PointsAccount account = pointsAccountService.getOrCreateAccount(order.getMemberId());

            // Add points back to account
            pointsAccountService.addPoints(order.getMemberId(), returnedPoints, account.getVersion());

            // Insert refund EARN transaction
            PointsTransaction refundTransaction = new PointsTransaction();
            refundTransaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("R"));
            refundTransaction.setMemberId(order.getMemberId());
            refundTransaction.setTransactionType(PointsTransactionTypeEnum.EARN.getCode());
            refundTransaction.setSource(PointsSourceEnum.REFUND.getCode());
            refundTransaction.setPointsAmount(returnedPoints);
            refundTransaction.setBalanceBefore(account.getAvailablePoints());
            refundTransaction.setBalanceAfter(account.getAvailablePoints() + returnedPoints);
            refundTransaction.setFrozenBefore(account.getFrozenPoints());
            refundTransaction.setFrozenAfter(account.getFrozenPoints());
            refundTransaction.setReferenceId(order.getOrderNo());
            refundTransaction.setRemark("退款返还积分");
            refundTransaction.setOperator(request.getOperator());
            refundTransaction.setCreatedAt(LocalDateTime.now());
            pointsTransactionMapper.insert(refundTransaction);

            // Insert new PointsBatch for returned points
            PointsBatch refundBatch = new PointsBatch();
            refundBatch.setMemberId(order.getMemberId());
            refundBatch.setSource(PointsSourceEnum.REFUND.getCode());
            refundBatch.setOriginalPoints(returnedPoints);
            refundBatch.setRemainingPoints(returnedPoints);
            refundBatch.setFrozenPoints(0L);
            refundBatch.setEarnTransactionId(refundTransaction.getId());
            refundBatch.setEarnedAt(LocalDateTime.now());
            refundBatch.setExpireAt(LocalDateTime.now().plusMonths(PointsConstants.DEFAULT_EXPIRE_MONTHS));
            refundBatch.setExpired(0);
            refundBatch.setCreatedAt(LocalDateTime.now());
            refundBatch.setUpdatedAt(LocalDateTime.now());
            pointsBatchMapper.insert(refundBatch);

            log.info("退款返还积分成功, memberId={}, returnedPoints={}, batchId={}",
                    order.getMemberId(), returnedPoints, refundBatch.getId());
        } else {
            log.info("会员在黑名单中，仅退还库存不返还积分, memberId={}", order.getMemberId());
        }

        // ========== 5. EVICT CACHES ==========
        cacheService.evictPointsAccount(order.getMemberId());
        if (inventory != null) {
            cacheService.evictBenefitInventory(inventory.getSkuCode());
        }

        // Log audit
        auditLogService.log("REFUND", "ORDER", order.getId(), order.getMemberId(),
                request.getOperator(),
                "退款订单: " + order.getOrderNo() + ", 原因: " + request.getReason()
                        + ", 返还积分: " + (isBlacklisted ? "否(黑名单)" : order.getPointsCost()));

        log.info("退款处理完成, orderId={}, orderNo={}", orderId, order.getOrderNo());

        // ========== 6. RETURN RESULT ==========
        return Result.ok("退款成功");
    }
}
