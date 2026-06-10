package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.membership.points.common.exception.ConcurrencyConflictException;
import com.membership.points.common.exception.InsufficientPointsException;
import com.membership.points.dto.response.PointsAccountResponse;
import com.membership.points.entity.Member;
import com.membership.points.entity.MemberLevel;
import com.membership.points.entity.PointsAccount;
import com.membership.points.mapper.MemberLevelMapper;
import com.membership.points.mapper.MemberMapper;
import com.membership.points.mapper.PointsAccountMapper;
import com.membership.points.service.CacheService;
import com.membership.points.service.PointsAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 积分账户服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsAccountServiceImpl implements PointsAccountService {

    private final PointsAccountMapper pointsAccountMapper;
    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final CacheService cacheService;

    @Transactional
    @Override
    public PointsAccount getOrCreateAccount(Long memberId) {
        LambdaQueryWrapper<PointsAccount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsAccount::getMemberId, memberId);
        PointsAccount account = pointsAccountMapper.selectOne(wrapper);

        if (account == null) {
            account = new PointsAccount();
            account.setMemberId(memberId);
            account.setAvailablePoints(0L);
            account.setFrozenPoints(0L);
            account.setTotalEarned(0L);
            account.setTotalSpent(0L);
            account.setTotalExpired(0L);
            account.setVersion(0);
            account.setCreatedAt(LocalDateTime.now());
            account.setUpdatedAt(LocalDateTime.now());
            pointsAccountMapper.insert(account);
            log.info("Created new points account for memberId: {}", memberId);
        }

        return account;
    }

    @Override
    public PointsAccountResponse getAccountResponse(Long memberId) {
        PointsAccountResponse cached = cacheService.getPointsAccount(memberId);
        if (cached != null) {
            return cached;
        }

        PointsAccount account = getOrCreateAccount(memberId);
        Member member = memberMapper.selectById(memberId);
        MemberLevel level = null;
        if (member != null && member.getLevelId() != null) {
            level = memberLevelMapper.selectById(member.getLevelId());
        }

        PointsAccountResponse response = new PointsAccountResponse();
        response.setMemberId(memberId);
        response.setAvailablePoints(account.getAvailablePoints());
        response.setFrozenPoints(account.getFrozenPoints());
        response.setTotalEarned(account.getTotalEarned());
        response.setTotalSpent(account.getTotalSpent());
        response.setTotalExpired(account.getTotalExpired());

        if (member != null) {
            response.setMemberNo(member.getMemberNo());
            response.setMemberName(member.getName());
        }
        if (level != null) {
            response.setLevelCode(level.getLevelCode());
            response.setLevelName(level.getLevelName());
        }

        cacheService.putPointsAccount(memberId, response);
        return response;
    }

    @Transactional
    @Override
    public void addPoints(Long memberId, long points, int version) {
        LambdaUpdateWrapper<PointsAccount> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PointsAccount::getMemberId, memberId)
                .eq(PointsAccount::getVersion, version)
                .setSql("available_points = available_points + " + points)
                .setSql("total_earned = total_earned + " + points)
                .setSql("version = version + 1")
                .set(PointsAccount::getUpdatedAt, LocalDateTime.now());

        int rows = pointsAccountMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new ConcurrencyConflictException("Failed to add points due to concurrent modification, memberId: " + memberId);
        }

        cacheService.evictPointsAccount(memberId);
        log.debug("Added {} points for memberId: {}", points, memberId);
    }

    @Transactional
    @Override
    public void deductPoints(Long memberId, long points, int version) {
        LambdaQueryWrapper<PointsAccount> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PointsAccount::getMemberId, memberId);
        PointsAccount account = pointsAccountMapper.selectOne(queryWrapper);

        if (account == null || account.getAvailablePoints() < points) {
            throw new InsufficientPointsException("Insufficient available points for memberId: " + memberId
                    + ", required: " + points + ", available: " + (account == null ? 0 : account.getAvailablePoints()));
        }

        LambdaUpdateWrapper<PointsAccount> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PointsAccount::getMemberId, memberId)
                .eq(PointsAccount::getVersion, version)
                .setSql("available_points = available_points - " + points)
                .setSql("total_spent = total_spent + " + points)
                .setSql("version = version + 1")
                .set(PointsAccount::getUpdatedAt, LocalDateTime.now());

        int rows = pointsAccountMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new ConcurrencyConflictException("Failed to deduct points due to concurrent modification, memberId: " + memberId);
        }

        cacheService.evictPointsAccount(memberId);
        log.debug("Deducted {} points for memberId: {}", points, memberId);
    }

    @Transactional
    @Override
    public void freezePoints(Long memberId, long points, int version) {
        LambdaQueryWrapper<PointsAccount> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PointsAccount::getMemberId, memberId);
        PointsAccount account = pointsAccountMapper.selectOne(queryWrapper);

        if (account == null || account.getAvailablePoints() < points) {
            throw new InsufficientPointsException("Insufficient available points to freeze for memberId: " + memberId
                    + ", required: " + points + ", available: " + (account == null ? 0 : account.getAvailablePoints()));
        }

        LambdaUpdateWrapper<PointsAccount> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PointsAccount::getMemberId, memberId)
                .eq(PointsAccount::getVersion, version)
                .setSql("available_points = available_points - " + points)
                .setSql("frozen_points = frozen_points + " + points)
                .setSql("version = version + 1")
                .set(PointsAccount::getUpdatedAt, LocalDateTime.now());

        int rows = pointsAccountMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new ConcurrencyConflictException("Failed to freeze points due to concurrent modification, memberId: " + memberId);
        }

        cacheService.evictPointsAccount(memberId);
        log.debug("Froze {} points for memberId: {}", points, memberId);
    }

    @Transactional
    @Override
    public void unfreezeAndDeduct(Long memberId, long points, int version) {
        LambdaUpdateWrapper<PointsAccount> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PointsAccount::getMemberId, memberId)
                .eq(PointsAccount::getVersion, version)
                .setSql("frozen_points = frozen_points - " + points)
                .setSql("total_spent = total_spent + " + points)
                .setSql("version = version + 1")
                .set(PointsAccount::getUpdatedAt, LocalDateTime.now());

        int rows = pointsAccountMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new ConcurrencyConflictException("Failed to unfreeze and deduct points due to concurrent modification, memberId: " + memberId);
        }

        cacheService.evictPointsAccount(memberId);
        log.debug("Unfroze and deducted {} points for memberId: {}", points, memberId);
    }

    @Transactional
    @Override
    public void unfreezeAndRestore(Long memberId, long points, int version) {
        LambdaUpdateWrapper<PointsAccount> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PointsAccount::getMemberId, memberId)
                .eq(PointsAccount::getVersion, version)
                .setSql("frozen_points = frozen_points - " + points)
                .setSql("available_points = available_points + " + points)
                .setSql("version = version + 1")
                .set(PointsAccount::getUpdatedAt, LocalDateTime.now());

        int rows = pointsAccountMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new ConcurrencyConflictException("Failed to unfreeze and restore points due to concurrent modification, memberId: " + memberId);
        }

        cacheService.evictPointsAccount(memberId);
        log.debug("Unfroze and restored {} points for memberId: {}", points, memberId);
    }

    @Transactional
    @Override
    public void expirePoints(Long memberId, long points, int version) {
        LambdaUpdateWrapper<PointsAccount> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PointsAccount::getMemberId, memberId)
                .eq(PointsAccount::getVersion, version)
                .setSql("available_points = available_points - " + points)
                .setSql("total_expired = total_expired + " + points)
                .setSql("version = version + 1")
                .set(PointsAccount::getUpdatedAt, LocalDateTime.now());

        int rows = pointsAccountMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new ConcurrencyConflictException("Failed to expire points due to concurrent modification, memberId: " + memberId);
        }

        cacheService.evictPointsAccount(memberId);
        log.debug("Expired {} points for memberId: {}", points, memberId);
    }
}
