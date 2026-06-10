package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.membership.points.common.enums.PointsTransactionTypeEnum;
import com.membership.points.common.exception.BusinessException;
import com.membership.points.dto.response.MemberLevelResponse;
import com.membership.points.entity.Member;
import com.membership.points.entity.MemberLevel;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.mapper.MemberLevelMapper;
import com.membership.points.mapper.MemberMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.MemberLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会员等级服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberLevelServiceImpl implements MemberLevelService {

    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final PointsTransactionMapper pointsTransactionMapper;
    private final AuditLogService auditLogService;

    @Override
    public MemberLevel getCurrentLevel(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException("Member not found: " + memberId);
        }
        if (member.getLevelId() == null) {
            return null;
        }
        return memberLevelMapper.selectById(member.getLevelId());
    }

    @Override
    public MemberLevelResponse getLevelResponse(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException("Member not found: " + memberId);
        }

        MemberLevel currentLevel = null;
        if (member.getLevelId() != null) {
            currentLevel = memberLevelMapper.selectById(member.getLevelId());
        }

        MemberLevelResponse response = new MemberLevelResponse();
        response.setMemberId(memberId);
        response.setMemberNo(member.getMemberNo());
        response.setMemberName(member.getName());
        response.setAccumulatedPoints(member.getAccumulatedPoints());

        if (currentLevel != null) {
            response.setLevelCode(currentLevel.getLevelCode());
            response.setLevelName(currentLevel.getLevelName());
            response.setLevelMultiplier(currentLevel.getLevelMultiplier());

            // Find next level
            LambdaQueryWrapper<MemberLevel> nextLevelWrapper = new LambdaQueryWrapper<>();
            nextLevelWrapper.gt(MemberLevel::getSortOrder, currentLevel.getSortOrder())
                    .orderByAsc(MemberLevel::getSortOrder)
                    .last("LIMIT 1");
            MemberLevel nextLevel = memberLevelMapper.selectOne(nextLevelWrapper);

            if (nextLevel != null) {
                response.setNextLevelMinPoints(nextLevel.getMinPoints());
                long pointsNeeded = nextLevel.getMinPoints() - member.getAccumulatedPoints();
                response.setPointsToNextLevel(pointsNeeded > 0 ? pointsNeeded : 0L);
            }
            // If nextLevel is null, member is at max level; nextLevelMinPoints and pointsToNextLevel remain null
        }

        return response;
    }

    @Transactional
    @Override
    public boolean checkAndUpgrade(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException("Member not found: " + memberId);
        }

        Long accumulatedPoints = member.getAccumulatedPoints();
        MemberLevel appropriateLevel = findAppropriateLevel(accumulatedPoints);

        if (appropriateLevel == null) {
            return false;
        }

        if (!appropriateLevel.getId().equals(member.getLevelId())) {
            Long oldLevelId = member.getLevelId();

            LambdaUpdateWrapper<Member> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Member::getId, memberId)
                    .set(Member::getLevelId, appropriateLevel.getId())
                    .set(Member::getUpdatedAt, LocalDateTime.now());
            memberMapper.update(null, updateWrapper);

            auditLogService.log("LEVEL_UPGRADE", "MEMBER", memberId, memberId, "SYSTEM",
                    "Level changed from levelId=" + oldLevelId + " to levelId=" + appropriateLevel.getId()
                            + ", accumulatedPoints=" + accumulatedPoints);

            log.info("Member {} level upgraded to {}", memberId, appropriateLevel.getLevelName());
            return true;
        }

        return false;
    }

    @Transactional
    @Override
    public void recalculateLevel(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException("Member not found: " + memberId);
        }

        // Sum EARN transactions from last 12 months
        LocalDateTime twelveMonthsAgo = LocalDateTime.now().minusMonths(12);
        LambdaQueryWrapper<PointsTransaction> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsTransaction::getMemberId, memberId)
                .eq(PointsTransaction::getTransactionType, PointsTransactionTypeEnum.EARN.getCode())
                .ge(PointsTransaction::getCreatedAt, twelveMonthsAgo);

        List<PointsTransaction> transactions = pointsTransactionMapper.selectList(wrapper);
        long totalEarnedInPeriod = transactions.stream()
                .mapToLong(PointsTransaction::getPointsAmount)
                .sum();

        MemberLevel appropriateLevel = findAppropriateLevel(totalEarnedInPeriod);

        if (appropriateLevel != null && !appropriateLevel.getId().equals(member.getLevelId())) {
            Long oldLevelId = member.getLevelId();

            LambdaUpdateWrapper<Member> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Member::getId, memberId)
                    .set(Member::getLevelId, appropriateLevel.getId())
                    .set(Member::getUpdatedAt, LocalDateTime.now());
            memberMapper.update(null, updateWrapper);

            auditLogService.log("LEVEL_RECALCULATE", "MEMBER", memberId, memberId, "SYSTEM",
                    "Level recalculated from levelId=" + oldLevelId + " to levelId=" + appropriateLevel.getId()
                            + ", earnedInLast12Months=" + totalEarnedInPeriod);

            log.info("Member {} level recalculated to {}", memberId, appropriateLevel.getLevelName());
        }
    }

    /**
     * Find the appropriate level based on accumulated points.
     * Matches where min_points <= points and (max_points >= points or max_points = 0).
     */
    private MemberLevel findAppropriateLevel(long accumulatedPoints) {
        LambdaQueryWrapper<MemberLevel> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(MemberLevel::getMinPoints, accumulatedPoints)
                .and(w -> w.ge(MemberLevel::getMaxPoints, accumulatedPoints)
                        .or()
                        .eq(MemberLevel::getMaxPoints, 0))
                .orderByDesc(MemberLevel::getSortOrder)
                .last("LIMIT 1");
        return memberLevelMapper.selectOne(wrapper);
    }
}
