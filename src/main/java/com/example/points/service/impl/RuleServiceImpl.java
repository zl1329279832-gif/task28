package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.RuleUpdateRequest;
import com.example.points.entity.PointsRule;
import com.example.points.mapper.PointsRuleMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.RuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleServiceImpl implements RuleService {

    private final PointsRuleMapper pointsRuleMapper;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;

    private static final String RULES_CACHE_KEY = "cache:points:rules";

    @Override
    public List<PointsRule> listRules() {
        LambdaQueryWrapper<PointsRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(PointsRule::getRuleCode)
                .orderByDesc(PointsRule::getVersion);
        return pointsRuleMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsRule createRule(PointsRule rule) {
        rule.setVersion(1);
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        rule.setCreateTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        pointsRuleMapper.insert(rule);
        invalidateRulesCache();
        log.info("Rule created: ruleCode={}, ruleName={}", rule.getRuleCode(), rule.getRuleName());
        return rule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PointsRule updateRule(RuleUpdateRequest request) {
        // 1. Find existing rule by id
        PointsRule existingRule = pointsRuleMapper.selectById(request.getRuleId());
        if (existingRule == null) {
            throw new BusinessException("规则不存在，ruleId=" + request.getRuleId());
        }

        String beforeValue = existingRule.toString();

        // 2. Set old version status=0 (disabled)
        existingRule.setStatus(0);
        existingRule.setUpdateTime(LocalDateTime.now());
        pointsRuleMapper.updateById(existingRule);

        // 3. Create new version: copy rule, increment version, apply updates
        PointsRule newRule = PointsRule.builder()
                .ruleCode(existingRule.getRuleCode())
                .ruleName(request.getRuleName() != null ? request.getRuleName() : existingRule.getRuleName())
                .ruleType(existingRule.getRuleType())
                .ruleValue(request.getRuleValue() != null ? request.getRuleValue() : existingRule.getRuleValue())
                .priority(existingRule.getPriority())
                .version(existingRule.getVersion() + 1)
                .status(request.getStatus() != null ? request.getStatus() : 1)
                .effectiveStart(request.getEffectiveStart() != null ? request.getEffectiveStart() : existingRule.getEffectiveStart())
                .effectiveEnd(request.getEffectiveEnd() != null ? request.getEffectiveEnd() : existingRule.getEffectiveEnd())
                .description(existingRule.getDescription())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        pointsRuleMapper.insert(newRule);

        // 4. Invalidate rules cache
        invalidateRulesCache();

        // 5. Log audit with before/after values
        String afterValue = newRule.toString();
        auditLogService.log("RULE", "UPDATE", String.valueOf(request.getRuleId()), "POINTS_RULE",
                beforeValue, afterValue, request.getOperator(), null);

        log.info("Rule updated: ruleId={}, ruleCode={}, newVersion={}",
                request.getRuleId(), existingRule.getRuleCode(), newRule.getVersion());
        return newRule;
    }

    private void invalidateRulesCache() {
        RBucket<Object> bucket = redissonClient.getBucket(RULES_CACHE_KEY);
        bucket.delete();
        log.info("Rules cache invalidated");
    }
}
