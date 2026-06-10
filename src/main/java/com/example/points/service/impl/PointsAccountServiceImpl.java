package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.entity.PointsAccount;
import com.example.points.mapper.PointsAccountMapper;
import com.example.points.service.PointsAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsAccountServiceImpl implements PointsAccountService {

    private final PointsAccountMapper pointsAccountMapper;
    private final RedissonClient redissonClient;

    @Override
    public PointsAccount getOrCreateAccount(Long memberId) {
        PointsAccount account = queryByMemberId(memberId);
        if (account != null) {
            return account;
        }

        String lockKey = "lock:account:create:" + memberId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("获取锁失败，请稍后重试");
            }
            // Double check after acquiring lock
            account = queryByMemberId(memberId);
            if (account != null) {
                return account;
            }

            account = PointsAccount.builder()
                    .memberId(memberId)
                    .availablePoints(0L)
                    .frozenPoints(0L)
                    .totalEarned(0L)
                    .totalConsumed(0L)
                    .totalExpired(0L)
                    .levelId(1L)
                    .monthlyEarned(0L)
                    .monthlyResetDate(LocalDate.now())
                    .status(1)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            pointsAccountMapper.insert(account);
            log.info("Created new points account for memberId={}", memberId);
            return account;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("创建积分账户被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public PointsAccount getAccount(Long memberId) {
        PointsAccount account = queryByMemberId(memberId);
        if (account == null) {
            throw new BusinessException("积分账户不存在，memberId=" + memberId);
        }
        return account;
    }

    private PointsAccount queryByMemberId(Long memberId) {
        LambdaQueryWrapper<PointsAccount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsAccount::getMemberId, memberId);
        return pointsAccountMapper.selectOne(wrapper);
    }
}
