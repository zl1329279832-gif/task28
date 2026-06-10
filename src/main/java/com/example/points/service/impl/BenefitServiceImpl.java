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
        // 1. Check blacklist
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单");
        }

        // 2. Idempotent check (fast path) — return existing record instead of throwing
        PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
        if (existingFlow != null) {
            log.info("Redeem event already processed, eventId={}", request.getEventId());
            return findExchangeRecord(request.getMemberId(), request.getBenefitId(), request.getBizOrderNo());
        }

        // 3. Acquire distributed lock
        String lockKey = "lock:benefit:redeem:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 4. Transaction inside lock — commits before lock release
            return transactionTemplate.execute(status -> {
                // Double-check idempotent inside lock
                PointsFlow existing = flowService.checkIdempotent(request.getEventId());
                if (existing != null) {
                    return findExchangeRecord(request.getMemberId(), request.getBenefitId(), request.getBizOrderNo());
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

                // Deduct points + Decrement stock (atomic within transaction)
                int rows = accountMapper.deductPoints(request.getMemberId(), benefit.getPointsCost());
                if (rows == 0) {
                    throw new BusinessException("积分扣减失败");
                }
                int stockRows = benefitMapper.decrementStock(request.getBenefitId());
                if (stockRows == 0) {
                    // Transaction will rollback, restoring the deducted points
                    throw new BusinessException("库存不足");
                }

                // Create exchange record
                String exchangeNo = "EX" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
                ExchangeRecord record = ExchangeRecord.builder()
                        .memberId(request.getMemberId())
                        .benefitId(request.getBenefitId())
                        .exchangeNo(exchangeNo)
                        .pointsCost(benefit.getPointsCost())
                        .status(1)
                        .bizOrderNo(request.getBizOrderNo())
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
                        .beforePoints(account.getAvailablePoints())
                        .afterPoints(account.getAvailablePoints() - benefit.getPointsCost())
                        .remark("兑换权益:" + benefit.getBenefitName())
                        .createTime(LocalDateTime.now())
                        .build();
                flowService.saveFlow(flow);

                auditLogService.log("BENEFIT", "REDEEM", String.valueOf(request.getMemberId()),
                        "MEMBER", String.valueOf(account.getAvailablePoints()),
                        String.valueOf(account.getAvailablePoints() - benefit.getPointsCost()),
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
        // 1. Idempotent check via eventId
        if (eventId != null) {
            PointsFlow existingFlow = flowService.checkIdempotent(eventId);
            if (existingFlow != null) {
                log.info("Exchange refund already processed, eventId={}", eventId);
                return;
            }
        }

        // 2. Find exchange record
        LambdaQueryWrapper<ExchangeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExchangeRecord::getBizOrderNo, bizOrderNo)
               .last("LIMIT 1");
        ExchangeRecord record = exchangeRecordMapper.selectOne(wrapper);
        if (record == null) {
            throw new BusinessException("兑换记录不存在");
        }

        // 3. Check status — prevent double refund
        if (record.getStatus() == 3) {
            log.info("Exchange already refunded, bizOrderNo={}", bizOrderNo);
            return;
        }

        // 4. Acquire lock on memberId
        String lockKey = "lock:benefit:redeem:" + record.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            final Long memberId = record.getMemberId();
            final Long pointsCost = record.getPointsCost();
            final Long benefitId = record.getBenefitId();
            final Long recordId = record.getId();

            // 5. Transaction inside lock
            transactionTemplate.executeWithoutResult(status -> {
                // Re-check idempotent inside lock
                if (eventId != null) {
                    PointsFlow existing = flowService.checkIdempotent(eventId);
                    if (existing != null) {
                        return;
                    }
                }

                // Re-check status inside lock
                ExchangeRecord current = exchangeRecordMapper.selectById(recordId);
                if (current.getStatus() == 3) {
                    log.info("Exchange already refunded (double-check), bizOrderNo={}", bizOrderNo);
                    return;
                }

                // Refund points using refundPoints (does NOT inflate total_earned/monthly_earned)
                PointsAccount account = accountService.getAccount(memberId);
                long beforePoints = account.getAvailablePoints();
                accountMapper.refundPoints(memberId, pointsCost);
                long afterPoints = beforePoints + pointsCost;

                // Restore stock
                benefitMapper.incrementStock(benefitId);

                // Update record status to REFUNDED (3)
                current.setStatus(3);
                current.setRefundTime(LocalDateTime.now());
                current.setUpdateTime(LocalDateTime.now());
                exchangeRecordMapper.updateById(current);

                // Create refund flow record
                String refundEventId = eventId != null ? eventId : "REFUND_EX_" + bizOrderNo + "_" + System.currentTimeMillis();
                PointsFlow flow = PointsFlow.builder()
                        .memberId(memberId)
                        .eventId(refundEventId)
                        .eventType("REFUND")
                        .pointsChange(pointsCost)
                        .beforePoints(beforePoints)
                        .afterPoints(afterPoints)
                        .bizOrderNo(bizOrderNo)
                        .remark("权益兑换退款: " + bizOrderNo)
                        .createTime(LocalDateTime.now())
                        .build();
                flowService.saveFlow(flow);

                auditLogService.log("BENEFIT", "REFUND_EXCHANGE", String.valueOf(recordId),
                        "EXCHANGE_RECORD", "1", "3", operator, "");

                log.info("Exchange refunded: memberId={}, pointsCost={}, bizOrderNo={}",
                        memberId, pointsCost, bizOrderNo);
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

    private ExchangeRecord findExchangeRecord(Long memberId, Long benefitId, String bizOrderNo) {
        LambdaQueryWrapper<ExchangeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExchangeRecord::getMemberId, memberId)
               .eq(ExchangeRecord::getBenefitId, benefitId);
        if (bizOrderNo != null) {
            wrapper.eq(ExchangeRecord::getBizOrderNo, bizOrderNo);
        }
        wrapper.orderByDesc(ExchangeRecord::getCreateTime)
               .last("LIMIT 1");
        return exchangeRecordMapper.selectOne(wrapper);
    }
}
