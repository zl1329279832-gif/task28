package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.membership.points.common.enums.PointsSourceEnum;
import com.membership.points.common.exception.BusinessException;
import com.membership.points.entity.Member;
import com.membership.points.entity.MemberLevel;
import com.membership.points.entity.PointsRule;
import com.membership.points.entity.PointsRuleVersion;
import com.membership.points.mapper.MemberLevelMapper;
import com.membership.points.mapper.MemberMapper;
import com.membership.points.mapper.PointsRuleMapper;
import com.membership.points.mapper.PointsRuleVersionMapper;
import com.membership.points.service.PointsRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 积分规则服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsRuleServiceImpl implements PointsRuleService {

    private final PointsRuleMapper pointsRuleMapper;
    private final PointsRuleVersionMapper pointsRuleVersionMapper;
    private final MemberMapper memberMapper;
    private final MemberLevelMapper memberLevelMapper;

    @Override
    public PointsRule getRuleBySource(String source) {
        LambdaQueryWrapper<PointsRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsRule::getSource, source)
                .eq(PointsRule::getEnabled, 1);
        PointsRule rule = pointsRuleMapper.selectOne(wrapper);
        if (rule == null) {
            throw new BusinessException("No enabled rule found for source: " + source);
        }
        return rule;
    }

    @Override
    public PointsRuleVersion getCurrentVersion(Long ruleId) {
        LambdaQueryWrapper<PointsRuleVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsRuleVersion::getRuleId, ruleId)
                .isNull(PointsRuleVersion::getEffectiveTo);
        PointsRuleVersion version = pointsRuleVersionMapper.selectOne(wrapper);
        if (version == null) {
            throw new BusinessException("No current version found for ruleId: " + ruleId);
        }
        return version;
    }

    @Override
    public long calculatePoints(String source, BigDecimal orderAmount, Long memberId) {
        PointsRule rule = getRuleBySource(source);
        PointsRuleVersion version = getCurrentVersion(rule.getId());

        BigDecimal points;

        if (PointsSourceEnum.PURCHASE.getCode().equals(source)) {
            // For purchase: points = orderAmount * pointsPerYuan * multiplier
            BigDecimal pointsPerYuan = BigDecimal.valueOf(version.getPointsPerYuan());
            points = orderAmount.multiply(pointsPerYuan).multiply(version.getMultiplier());
        } else {
            // For others: points = basePoints * multiplier
            points = BigDecimal.valueOf(version.getBasePoints()).multiply(version.getMultiplier());
        }

        // Apply member level multiplier
        Member member = memberMapper.selectById(memberId);
        if (member != null && member.getLevelId() != null) {
            MemberLevel level = memberLevelMapper.selectById(member.getLevelId());
            if (level != null && level.getLevelMultiplier() != null) {
                points = points.multiply(level.getLevelMultiplier());
            }
        }

        // Floor to integer
        long result = points.setScale(0, RoundingMode.FLOOR).longValue();
        log.debug("Calculated {} points for source={}, memberId={}, orderAmount={}", result, source, memberId, orderAmount);
        return result;
    }

    @Transactional
    @Override
    public PointsRuleVersion createNewVersion(Long ruleId, PointsRule updatedRule) {
        // Close current version
        PointsRuleVersion currentVersion = getCurrentVersion(ruleId);
        LambdaUpdateWrapper<PointsRuleVersion> closeWrapper = new LambdaUpdateWrapper<>();
        closeWrapper.eq(PointsRuleVersion::getId, currentVersion.getId())
                .set(PointsRuleVersion::getEffectiveTo, LocalDateTime.now());
        pointsRuleVersionMapper.update(null, closeWrapper);

        // Create new version
        int newVersionNumber = currentVersion.getVersionNumber() + 1;

        PointsRuleVersion newVersion = new PointsRuleVersion();
        newVersion.setRuleId(ruleId);
        newVersion.setVersionNumber(newVersionNumber);
        newVersion.setBasePoints(updatedRule.getBasePoints());
        newVersion.setMultiplier(updatedRule.getMultiplier());
        newVersion.setPointsPerYuan(updatedRule.getPointsPerYuan());
        newVersion.setMonthlyCap(updatedRule.getMonthlyCap());
        newVersion.setExpireMonths(updatedRule.getExpireMonths());
        newVersion.setEffectiveFrom(LocalDateTime.now());
        newVersion.setEffectiveTo(null);
        newVersion.setCreatedAt(LocalDateTime.now());
        pointsRuleVersionMapper.insert(newVersion);

        // Update rule's current version number
        LambdaUpdateWrapper<PointsRule> ruleUpdateWrapper = new LambdaUpdateWrapper<>();
        ruleUpdateWrapper.eq(PointsRule::getId, ruleId)
                .set(PointsRule::getCurrentVersion, newVersionNumber)
                .set(PointsRule::getUpdatedAt, LocalDateTime.now());
        pointsRuleMapper.update(null, ruleUpdateWrapper);

        log.info("Created new rule version {} for ruleId: {}", newVersionNumber, ruleId);
        return newVersion;
    }
}
