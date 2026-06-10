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
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(rollbackFor = Exception.class)
    public ExchangeRecord redeem(RedeemRequest request) {
        // 1. Check blacklist
        if (blacklistService.isBlacklisted(request.getMemberId())) {
            throw new BusinessException("会员已被加入黑名单");
        }

        // 2. Idempotent check
        PointsFlow existingFlow = flowService.checkIdempotent(request.getEventId());
        if (existingFlow != null) {
            throw new BusinessException("重复兑换请求");
        }

        // 3. Acquire distributed lock
        String lockKey = "lock:benefit:redeem:" + request.getMemberId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 4. Get benefit
            Benefit benefit = benefitMapper.selectById(request.getBenefitId());
            if (benefit == null || benefit.getStatus() != 1) {
                throw new BusinessException("权益不存在或已下架");
            }

            // 5. Check time range (null-safe)
            LocalDateTime now = LocalDateTime.now();
            if (benefit.getStartTime() != null && now.isBefore(benefit.getStartTime())) {
                throw new BusinessException("权益尚未上架");
            }
            if (benefit.getEndTime() != null && now.isAfter(benefit.getEndTime())) {
                throw new BusinessException("权益已下架");
            }

            // 6. Check stock
            if (benefit.getAvailableStock() == null || benefit.getAvailableStock() <= 0) {
                throw new BusinessException("权益库存不足");
            }

            // 7. Get account
            PointsAccount account = accountService.getAccount(request.getMemberId());
            if (account == null) {
                throw new BusinessException("会员积分账户不存在");
            }

            // 8. Check member level (null-safe)
            if (benefit.getMinLevelId() != null && benefit.getMinLevelId() > 0) {
                MemberLevel memberLevel = memberLevelMapper.selectById(account.getLevelId());
                if (memberLevel == null || memberLevel.getId() < benefit.getMinLevelId()) {
                    throw new BusinessException("会员等级不满足兑换条件");
                }
            }

            // 9. Check daily limit (0 = unlimited)
            if (benefit.getDailyLimit() != null && benefit.getDailyLimit() > 0) {
                int dailyCount = flowMapper.countDailyExchange(request.getMemberId(), request.getBenefitId());
                if (dailyCount >= benefit.getDailyLimit()) {
                    throw new BusinessException("已达到每日兑换上限");
                }
            }

            // 10. Check total limit (0 = unlimited)
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

            // 11. Check available points
            if (account.getAvailablePoints() < benefit.getPointsCost()) {
                throw new BusinessException("可用积分不足");
            }

            // 12. Deduct points + Decrement stock (atomic within @Transactional)
            int rows = accountMapper.deductPoints(request.getMemberId(), benefit.getPointsCost());
            if (rows == 0) {
                throw new BusinessException("积分扣减失败");
            }
            int stockRows = benefitMapper.decrementStock(request.getBenefitId());
            if (stockRows == 0) {
                throw new BusinessException("库存不足");
                // @Transactional rollback restores the deducted points
            }

            // 13. Create exchange record
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

            // 14. Save points flow
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
    @Transactional(rollbackFor = Exception.class)
    public void refundExchange(String bizOrderNo, String eventId, String operator) {
        // Find exchange record
        LambdaQueryWrapper<ExchangeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExchangeRecord::getBizOrderNo, bizOrderNo)
               .last("LIMIT 1");
        ExchangeRecord record = exchangeRecordMapper.selectOne(wrapper);
        if (record == null) {
            throw new BusinessException("兑换记录不存在");
        }

        // Refund points
        accountMapper.addPoints(record.getMemberId(), record.getPointsCost());

        // Restore stock
        benefitMapper.incrementStock(record.getBenefitId());

        // Update record status to REFUNDED (3)
        record.setStatus(3);
        record.setRefundTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        exchangeRecordMapper.updateById(record);

        auditLogService.log("BENEFIT", "REFUND_EXCHANGE", String.valueOf(record.getId()),
                "EXCHANGE_RECORD", "1", "3", operator, "");
    }
}
