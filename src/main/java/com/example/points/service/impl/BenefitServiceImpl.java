package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.RedeemRequest;
import com.example.points.entity.*;
import com.example.points.mapper.*;
import com.example.points.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BenefitServiceImpl implements BenefitService {

    private final BenefitMapper benefitMapper;
    private final ExchangeRecordMapper exchangeRecordMapper;
    private final PointsAccountMapper accountMapper;
    private final PointsFlowMapper flowMapper;
    private final PointsFlowService flowService;
    private final PointsAccountService accountService;
    private final BlacklistService blacklistService;
    private final AuditLogService auditLogService;
    private final MemberLevelMapper memberLevelMapper;
    private final RedissonClient redissonClient;
    private final BudgetPoolService budgetPoolService;
    private final RiskControlService riskControlService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public List<Benefit> listActiveBenefits(Long memberId) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Benefit> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Benefit::getStatus, 1)
               .gt(Benefit::getAvailableStock, 0)
               .and(w -> w.isNull(Benefit::getStartTime).or().le(Benefit::getStartTime, now))
               .and(w -> w.isNull(Benefit::getEndTime).or().ge(Benefit::getEndTime, now));
        return benefitMapper.selectList(wrapper);
    }

    @Override
    public ExchangeRecord redeem(RedeemRequest request) {
        // 1. Fast-fail blacklist check
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单");
        }

        // 2. Idempotent check - return existing record instead of throwing
        PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
        if (existingFlow != null) {
            log.info("Redeem event already processed, eventId={}", request.getEventId());
            LambdaQueryWrapper<ExchangeRecord> recordWrapper = new LambdaQueryWrapper<>();
            recordWrapper.eq(ExchangeRecord::getMemberId, request.getMemberId())
                       .eq(ExchangeRecord::getBenefitId, request.getBenefitId())
                       .eq(ExchangeRecord::getStatus, 1)
                       .orderByDesc(ExchangeRecord::getCreateTime)
                       .last("LIMIT 1");
            return exchangeRecordMapper.selectOne(recordWrapper);
        }

        // 3. Acquire distributed lock
        String lockKey = "lock:benefit:redeem:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 4. Execute within transaction INSIDE lock
            return transactionTemplate.execute(status -> {
                // Double-check idempotent inside lock+transaction
                PointsFlow existFlow = flowService.checkIdempotent(request.getEventId());
                if (existFlow != null) {
                    LambdaQueryWrapper<ExchangeRecord> rw = new LambdaQueryWrapper<>();
                    rw.eq(ExchangeRecord::getMemberId, request.getMemberId())
                      .eq(ExchangeRecord::getBenefitId, request.getBenefitId())
                      .eq(ExchangeRecord::getStatus, 1)
                      .orderByDesc(ExchangeRecord::getCreateTime)
                      .last("LIMIT 1");
                    return exchangeRecordMapper.selectOne(rw);
                }

                // Double-check blacklist inside lock
                if (blacklistService.isBlacklisted(request.getMemberId())) {
                    throw new BusinessException("会员已被加入黑名单");
                }

                // Get benefit
                Benefit benefit = benefitMapper.selectById(request.getBenefitId());
                if (benefit == null || benefit.getStatus() != 1) {
                    throw new BusinessException("权益不存在或已下架");
                }

                // Check time range (null-safe)
                LocalDateTime now = LocalDateTime.now();
                if (benefit.getStartTime() != null && now.isBefore(benefit.getStartTime())) {
                    throw new BusinessException("权益尚未上架");
                }
                if (benefit.getEndTime() != null && now.isAfter(benefit.getEndTime())) {
                    throw new BusinessException("权益已下架");
                }

                // Check stock
                if (benefit.getAvailableStock() == null || benefit.getAvailableStock() <= 0) {
                    throw new BusinessException("权益库存不足");
                }

                // Get account
                PointsAccount account = accountService.getAccount(request.getMemberId());
                if (account == null) {
                    throw new BusinessException("会员积分账户不存在");
                }

                // Check member level (null-safe)
                if (benefit.getMinLevelId() != null && benefit.getMinLevelId() > 0) {
                    MemberLevel memberLevel = memberLevelMapper.selectById(account.getLevelId());
                    if (memberLevel == null || memberLevel.getId() < benefit.getMinLevelId()) {
                        throw new BusinessException("会员等级不满足兑换条件");
                    }
                }

                // Check daily limit (0 = unlimited)
                if (benefit.getDailyLimit() != null && benefit.getDailyLimit() > 0) {
                    int dailyCount = flowMapper.countDailyExchange(request.getMemberId(), request.getBenefitId());
                    if (dailyCount >= benefit.getDailyLimit()) {
                        throw new BusinessException("已达到每日兑换上限");
                    }
                }

                // Check total limit (0 = unlimited)
                if (benefit.getTotalLimit() != null && benefit.getTotalLimit() > 0) {
                    LambdaQueryWrapper<ExchangeRecord> totalWrapper = new LambdaQueryWrapper<>();
                    totalWrapper.eq(ExchangeRecord::getMemberId, request.getMemberId())
                               .eq(ExchangeRecord::getBenefitId, request.getBenefitId())
                               .eq(ExchangeRecord::getStatus, 1);
                    Long totalCount = exchangeRecordMapper.selectCount(totalWrapper);
                    if (totalCount != null && totalCount >= benefit.getTotalLimit()) {
                        throw new BusinessException("已达到总兑换上限");
                    }
                }

                // Check available points
                if (account.getAvailablePoints() < benefit.getPointsCost()) {
                    throw new BusinessException("可用积分不足");
                }

                // Budget pool check: use reserveBudget (tracks daily/monthly caps)
                if (request.getBudgetPoolId() != null) {
                    if (!riskControlService.isCircuitBreakerAllowing(request.getBudgetPoolId())) {
                        throw new BusinessException("熔断器已开启，兑换被阻止");
                    }
                    BudgetPool pool = budgetPoolService.getPool(request.getBudgetPoolId());
                    if (!budgetPoolService.isPoolValidFor(pool, account.getLevelId())) {
                        throw new BusinessException("预算池不适用于当前会员等级");
                    }
                    budgetPoolService.reserveBudget(request.getBudgetPoolId(), benefit.getPointsCost());
                }

                // Deduct points + Decrement stock (atomic within transaction)
                int rows = accountMapper.deductPoints(request.getMemberId(), benefit.getPointsCost());
                if (rows == 0) {
                    throw new BusinessException("积分扣减失败");
                }
                int stockRows = benefitMapper.decrementStock(request.getBenefitId());
                if (stockRows == 0) {
                    throw new BusinessException("库存不足");
                }

                // Re-read account AFTER deduction for accurate flow values
                PointsAccount updatedAccount = accountMapper.selectByMemberId(request.getMemberId());
                long afterPoints = updatedAccount.getAvailablePoints();
                long beforePoints = afterPoints + benefit.getPointsCost();

                // Create exchange record
                String exchangeNo = "EX" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
                ExchangeRecord record = ExchangeRecord.builder()
                        .memberId(request.getMemberId())
                        .benefitId(request.getBenefitId())
                        .exchangeNo(exchangeNo)
                        .pointsCost(benefit.getPointsCost())
                        .status(1)
                        .bizOrderNo(request.getBizOrderNo())
                        .budgetPoolId(request.getBudgetPoolId())
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build();
                exchangeRecordMapper.insert(record);

                // Save points flow
                PointsFlow flow = PointsFlow.builder()
                        .memberId(request.getMemberId())
                        .eventId(request.getEventId())
                        .eventType("REDEEM")
                        .pointsChange(-benefit.getPointsCost())
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .remark("兑换权益:" + benefit.getBenefitName())
                        .budgetPoolId(request.getBudgetPoolId())
                        .createTime(LocalDateTime.now())
                        .build();
                flowService.saveFlow(flow);

                auditLogService.log("BENEFIT", "REDEEM", String.valueOf(request.getMemberId()),
                        "MEMBER", String.valueOf(beforePoints),
                        String.valueOf(afterPoints),
                        "SYSTEM", null);

                return record;
            });
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
    public void refundExchange(String bizOrderNo, String eventId, String operator) {
        // 1. Idempotency check BEFORE lock
        PointsFlow existingFlow = flowService.checkIdempotent(eventId);
        if (existingFlow != null) {
            log.info("Refund exchange already processed, eventId={}", eventId);
            return;
        }

        // 2. Find exchange record
        LambdaQueryWrapper<ExchangeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExchangeRecord::getBizOrderNo, bizOrderNo)
               .last("LIMIT 1");
        ExchangeRecord record = exchangeRecordMapper.selectOne(wrapper);
        if (record == null) {
            throw new BusinessException("兑换记录不存在");
        }

        // 3. Check if already refunded
        if (record.getStatus() == 3) { // REFUNDED
            log.info("Exchange already refunded, bizOrderNo={}", bizOrderNo);
            return;
        }

        // 4. Acquire distributed lock
        String lockKey = "lock:benefit:refund:" + record.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 5. Execute within transaction INSIDE lock
            transactionTemplate.execute(status -> {
                // Double-check idempotency inside lock+transaction
                PointsFlow existFlow = flowService.checkIdempotent(eventId);
                if (existFlow != null) {
                    return null;
                }

                // Re-check status inside lock+transaction
                ExchangeRecord freshRecord = exchangeRecordMapper.selectOne(wrapper);
                if (freshRecord.getStatus() == 3) { // REFUNDED
                    return null;
                }

                // Execute refund
                accountMapper.addPoints(freshRecord.getMemberId(), freshRecord.getPointsCost());
                benefitMapper.incrementStock(freshRecord.getBenefitId());

                // Restore budget to original pool (use releaseBudget to restore daily/monthly counters)
                if (freshRecord.getBudgetPoolId() != null) {
                    budgetPoolService.releaseBudget(freshRecord.getBudgetPoolId(), freshRecord.getPointsCost());
                }

                freshRecord.setStatus(3); // REFUNDED
                freshRecord.setRefundTime(LocalDateTime.now());
                freshRecord.setUpdateTime(LocalDateTime.now());
                exchangeRecordMapper.updateById(freshRecord);

                // Re-read account for accurate flow
                PointsAccount updatedAccount = accountMapper.selectByMemberId(freshRecord.getMemberId());
                long afterPoints = updatedAccount.getAvailablePoints();
                long beforePoints = afterPoints - freshRecord.getPointsCost();

                // Create refund flow record
                PointsFlow flow = PointsFlow.builder()
                        .memberId(freshRecord.getMemberId())
                        .eventId(eventId)
                        .eventType("REFUND")
                        .pointsChange(freshRecord.getPointsCost())
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(bizOrderNo)
                        .budgetPoolId(freshRecord.getBudgetPoolId())
                        .remark("权益退款: " + bizOrderNo)
                        .createTime(LocalDateTime.now())
                        .build();
                flowService.saveFlow(flow);

                // Audit log
                auditLogService.log("BENEFIT", "REFUND_EXCHANGE", String.valueOf(freshRecord.getId()),
                        "EXCHANGE_RECORD", String.valueOf(beforePoints), String.valueOf(afterPoints),
                        operator, "");

                log.info("Refund exchange completed: bizOrderNo={}, memberId={}, refundPoints={}",
                        bizOrderNo, freshRecord.getMemberId(), freshRecord.getPointsCost());
                return null;
            });
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
