package com.membership.points.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.membership.points.common.constant.RedisKeyConstants;
import com.membership.points.common.enums.MemberLevelEnum;
import com.membership.points.common.enums.PointsSourceEnum;
import com.membership.points.common.enums.PointsTransactionTypeEnum;
import com.membership.points.common.enums.RedemptionStatusEnum;
import com.membership.points.common.exception.BlacklistedException;
import com.membership.points.common.exception.BusinessException;
import com.membership.points.common.exception.ConcurrencyConflictException;
import com.membership.points.common.exception.InsufficientPointsException;
import com.membership.points.common.exception.InsufficientStockException;
import com.membership.points.common.result.Result;
import com.membership.points.common.util.RedisLockUtil;
import com.membership.points.common.util.SnowflakeIdGenerator;
import com.membership.points.dto.request.RedeemPointsRequest;
import com.membership.points.dto.response.RedemptionOrderResponse;
import com.membership.points.entity.Benefit;
import com.membership.points.entity.BenefitInventory;
import com.membership.points.entity.MemberLevel;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.entity.RedemptionOrder;
import com.membership.points.mapper.BenefitInventoryMapper;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.mapper.RedemptionOrderMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.BenefitService;
import com.membership.points.service.BlacklistService;
import com.membership.points.service.CacheService;
import com.membership.points.service.IdempotentService;
import com.membership.points.service.MemberLevelService;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.RedemptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兑换服务实现类 - 核心兑换流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedemptionServiceImpl implements RedemptionService {

    private final IdempotentService idempotentService;
    private final BlacklistService blacklistService;
    private final BenefitService benefitService;
    private final PointsAccountService pointsAccountService;
    private final MemberLevelService memberLevelService;
    private final BenefitInventoryMapper benefitInventoryMapper;
    private final PointsBatchMapper pointsBatchMapper;
    private final PointsTransactionMapper pointsTransactionMapper;
    private final RedemptionOrderMapper redemptionOrderMapper;
    private final AuditLogService auditLogService;
    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Result<RedemptionOrderResponse> redeem(RedeemPointsRequest request) {
        // ========== 1. IDEMPOTENCY CHECK ==========
        if (!idempotentService.checkAndMark(request.getIdempotentKey(), "REDEEM")) {
            log.info("重复兑换请求, idempotentKey={}", request.getIdempotentKey());
            String cachedResult = idempotentService.getCachedResult(request.getIdempotentKey());
            if (cachedResult != null) {
                try {
                    RedemptionOrderResponse cachedResponse = objectMapper.readValue(cachedResult, RedemptionOrderResponse.class);
                    return Result.ok(cachedResponse);
                } catch (JsonProcessingException e) {
                    log.warn("反序列化缓存结果失败, idempotentKey={}", request.getIdempotentKey(), e);
                }
            }
            return Result.ok();
        }

        // ========== 2. BLACKLIST CHECK ==========
        if (blacklistService.isBlockedForRedeem(request.getMemberId())) {
            throw new BlacklistedException("会员已被限制兑换");
        }

        // ========== 3. VALIDATE BENEFIT ==========
        Benefit benefit = benefitService.getBenefitById(request.getBenefitId());
        if (benefit == null) {
            throw new BusinessException("权益不存在");
        }
        if (benefit.getEnabled() != 1) {
            throw new BusinessException("权益已下架");
        }

        // Check time range
        LocalDateTime now = LocalDateTime.now();
        if (benefit.getStartTime() != null && now.isBefore(benefit.getStartTime())) {
            throw new BusinessException("权益兑换未开始");
        }
        if (benefit.getEndTime() != null && now.isAfter(benefit.getEndTime())) {
            throw new BusinessException("权益兑换已结束");
        }

        // Check minLevel requirement
        if (benefit.getMinLevel() != null && !benefit.getMinLevel().isEmpty()) {
            MemberLevel currentLevel = memberLevelService.getCurrentLevel(request.getMemberId());
            MemberLevelEnum requiredLevel = MemberLevelEnum.valueOf(benefit.getMinLevel());
            if (currentLevel.getSortOrder() < requiredLevel.getCode()) {
                throw new BusinessException("会员等级不满足兑换要求");
            }
        }

        // Check inventory
        BenefitInventory inventory = benefitService.getInventoryBySkuCode(request.getSkuCode());
        if (inventory == null) {
            throw new BusinessException("库存信息不存在");
        }
        if (inventory.getAvailableStock() < request.getQuantity()) {
            throw new InsufficientStockException("库存不足");
        }

        // ========== 4. CALCULATE COST ==========
        long totalCost = (long) benefit.getPointsCost() * request.getQuantity();

        // ========== 5. ACQUIRE REDIS DISTRIBUTED LOCK ==========
        String lockKey = RedisKeyConstants.LOCK_REDEEM + request.getMemberId();
        if (!redisLockUtil.tryLock(lockKey, 5000, 10000)) {
            throw new ConcurrencyConflictException("请勿重复提交");
        }

        RedemptionOrder order;
        try {
            // ========== 6. TRANSACTIONAL BLOCK ==========
            order = transactionTemplate.execute(status -> {
                return executeRedeemTransaction(request, benefit, inventory, totalCost);
            });
        } finally {
            // ========== 7. RELEASE LOCK ==========
            redisLockUtil.unlock(lockKey);
        }

        // ========== 8. POST-TRANSACTION ==========
        cacheService.evictPointsAccount(request.getMemberId());
        cacheService.evictBenefitInventory(request.getSkuCode());

        // Build response
        RedemptionOrderResponse response = buildOrderResponse(order, benefit, request.getSkuCode());

        auditLogService.log("REDEEM", "ORDER", order.getId(), request.getMemberId(), null,
                "兑换权益: " + benefit.getBenefitName() + ", 数量: " + request.getQuantity()
                        + ", 消耗积分: " + totalCost + ", 订单号: " + order.getOrderNo());

        // Cache result for idempotency
        try {
            String resultJson = objectMapper.writeValueAsString(response);
            idempotentService.markResult(request.getIdempotentKey(), resultJson);
        } catch (JsonProcessingException e) {
            log.warn("序列化兑换结果失败, orderNo={}", order.getOrderNo(), e);
        }

        // ========== 9. RETURN RESULT ==========
        log.info("兑换成功, memberId={}, orderNo={}, pointsCost={}",
                request.getMemberId(), order.getOrderNo(), totalCost);
        return Result.ok(response);
    }

    /**
     * 执行兑换事务（在TransactionTemplate中运行）
     */
    private RedemptionOrder executeRedeemTransaction(RedeemPointsRequest request, Benefit benefit,
                                                      BenefitInventory inventory, long totalCost) {
        // a. Load fresh account
        PointsAccount account = pointsAccountService.getOrCreateAccount(request.getMemberId());
        if (account.getAvailablePoints() < totalCost) {
            throw new InsufficientPointsException("积分不足");
        }

        // b. (account check done above)

        // c. DEDUCT INVENTORY (optimistic lock via @Version)
        BenefitInventory freshInventory = benefitInventoryMapper.selectById(inventory.getId());
        freshInventory.setAvailableStock(freshInventory.getAvailableStock() - request.getQuantity());
        freshInventory.setUpdatedAt(LocalDateTime.now());
        int rows = benefitInventoryMapper.updateById(freshInventory);
        if (rows == 0) {
            throw new InsufficientStockException("库存不足，请重试");
        }

        // d. DEDUCT POINTS FROM BATCHES (FIFO by expire_at ASC)
        List<PointsBatch> batches = pointsBatchMapper.selectActiveBatchesByMemberId(request.getMemberId());
        long remaining = totalCost;
        for (PointsBatch batch : batches) {
            long deduct = Math.min(batch.getRemainingPoints(), remaining);
            batch.setRemainingPoints(batch.getRemainingPoints() - deduct);
            if (batch.getRemainingPoints() == 0 && batch.getFrozenPoints() == 0) {
                batch.setExpired(1);
            }
            batch.setUpdatedAt(LocalDateTime.now());
            pointsBatchMapper.updateById(batch);
            remaining -= deduct;
            if (remaining <= 0) {
                break;
            }
        }
        if (remaining > 0) {
            throw new InsufficientPointsException("积分批次不足");
        }

        // e. DEDUCT ACCOUNT BALANCE
        pointsAccountService.deductPoints(request.getMemberId(), totalCost, account.getVersion());

        // f. INSERT TRANSACTION
        PointsTransaction transaction = new PointsTransaction();
        transaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("S"));
        transaction.setMemberId(request.getMemberId());
        transaction.setTransactionType(PointsTransactionTypeEnum.SPEND.getCode());
        transaction.setSource(PointsSourceEnum.REDEMPTION.getCode());
        transaction.setPointsAmount(-totalCost);
        transaction.setBalanceBefore(account.getAvailablePoints());
        transaction.setBalanceAfter(account.getAvailablePoints() - totalCost);
        transaction.setFrozenBefore(account.getFrozenPoints());
        transaction.setFrozenAfter(account.getFrozenPoints());
        transaction.setCreatedAt(LocalDateTime.now());
        pointsTransactionMapper.insert(transaction);

        // g. INSERT REDEMPTION ORDER
        RedemptionOrder order = new RedemptionOrder();
        order.setOrderNo(SnowflakeIdGenerator.generateOrderNo());
        order.setMemberId(request.getMemberId());
        order.setBenefitId(request.getBenefitId());
        order.setInventoryId(inventory.getId());
        order.setQuantity(request.getQuantity());
        order.setPointsCost((int) totalCost);
        order.setStatus(RedemptionStatusEnum.COMPLETED.getCode());
        order.setCompletedAt(LocalDateTime.now());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        redemptionOrderMapper.insert(order);

        // Set referenceId on transaction to link to order
        transaction.setReferenceId(order.getOrderNo());
        pointsTransactionMapper.updateById(transaction);

        // h. Return order entity
        return order;
    }

    /**
     * 构建兑换订单响应
     */
    private RedemptionOrderResponse buildOrderResponse(RedemptionOrder order, Benefit benefit, String skuCode) {
        RedemptionOrderResponse response = new RedemptionOrderResponse();
        response.setOrderNo(order.getOrderNo());
        response.setMemberId(order.getMemberId());
        response.setBenefitName(benefit.getBenefitName());
        response.setSkuCode(skuCode);
        response.setQuantity(order.getQuantity());
        response.setPointsCost(order.getPointsCost());
        response.setStatus(order.getStatus());
        response.setCompletedAt(order.getCompletedAt());
        response.setCreatedAt(order.getCreatedAt());
        return response;
    }
}
