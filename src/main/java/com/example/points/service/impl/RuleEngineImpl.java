package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.dto.PointsEventRequest;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsRule;
import com.example.points.mapper.MemberLevelMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.mapper.PointsRuleMapper;
import com.example.points.service.RuleEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineImpl implements RuleEngine {

    private final PointsRuleMapper ruleMapper;
    private final PointsFlowMapper flowMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final RedissonClient redissonClient;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RULES_CACHE_KEY = "cache:points:rules";

    @Override
    public long calculatePoints(PointsEventRequest request, PointsAccount account) {
        List<PointsRule> rules = loadRules();

        // 1. Find matching rule for event type
        PointsRule matchingRule = rules.stream()
                .filter(r -> r.getRuleCode().equals(request.getEventType()) && r.getStatus() == 1)
                .findFirst()
                .orElse(null);

        if (matchingRule == null) {
            log.warn("No active rule found for eventType={}", request.getEventType());
            return 0L;
        }

        // 2. Calculate base points from rule
        long basePoints = calculateBasePoints(request, matchingRule);
        if (basePoints <= 0) {
            return 0L;
        }

        // 3. Apply ACTIVITY multiplier (if an activity rule is active and matches)
        PointsRule activityRule = rules.stream()
                .filter(r -> "ACTIVITY".equals(r.getRuleCode()) && r.getStatus() == 1)
                .findFirst()
                .orElse(null);
        if (activityRule != null && !"ACTIVITY".equals(request.getEventType())) {
            try {
                JsonNode actValue = OBJECT_MAPPER.readTree(activityRule.getRuleValue());
                String activityCode = request.getActivityCode();
                String ruleActivityCode = actValue.has("activityCode") ? actValue.get("activityCode").asText() : null;
                // Apply multiplier if activityCode matches or no specific code required
                if (activityCode != null && activityCode.equals(ruleActivityCode)) {
                    double multiplier = actValue.has("multiplier") ? actValue.get("multiplier").asDouble() : 1.0;
                    basePoints = (long) (basePoints * multiplier);
                    log.debug("Applied activity multiplier={}, basePoints={}", multiplier, basePoints);
                }
            } catch (Exception e) {
                log.error("Failed to parse activity rule", e);
            }
        }

        // 4. Apply LEVEL_BONUS (member level earn rate)
        PointsRule levelBonusRule = rules.stream()
                .filter(r -> "LEVEL_BONUS".equals(r.getRuleCode()) && r.getStatus() == 1)
                .findFirst()
                .orElse(null);
        if (levelBonusRule != null && account.getLevelId() != null) {
            try {
                JsonNode levelValue = OBJECT_MAPPER.readTree(levelBonusRule.getRuleValue());
                if (levelValue.has("useMemberLevel") && levelValue.get("useMemberLevel").asBoolean()) {
                    com.example.points.entity.MemberLevel level = memberLevelMapper.selectById(account.getLevelId());
                    if (level != null && level.getEarnRate() != null) {
                        basePoints = (long) (basePoints * level.getEarnRate().doubleValue());
                        log.debug("Applied level bonus rate={}, basePoints={}", level.getEarnRate(), basePoints);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to apply level bonus", e);
            }
        }

        // 5. Apply monthly cap
        PointsRule monthlyCapRule = rules.stream()
                .filter(r -> "MONTHLY_CAP".equals(r.getRuleCode()) && r.getStatus() == 1)
                .findFirst()
                .orElse(null);

        if (monthlyCapRule != null) {
            try {
                JsonNode capValue = OBJECT_MAPPER.readTree(monthlyCapRule.getRuleValue());
                long maxPoints = capValue.has("maxPoints") ? capValue.get("maxPoints").asLong() : Long.MAX_VALUE;
                long monthlyEarned = account.getMonthlyEarned() != null ? account.getMonthlyEarned() : 0L;
                long remaining = maxPoints - monthlyEarned;
                if (remaining <= 0) {
                    log.info("Member {} has reached monthly cap, monthlyEarned={}",
                            account.getMemberId(), monthlyEarned);
                    return 0L;
                }
                basePoints = Math.min(basePoints, remaining);
            } catch (Exception e) {
                log.error("Failed to parse monthly cap rule", e);
            }
        }

        return Math.max(basePoints, 0L);
    }

    private long calculateBasePoints(PointsEventRequest request, PointsRule rule) {
        try {
            JsonNode ruleValue = OBJECT_MAPPER.readTree(rule.getRuleValue());

            switch (request.getEventType()) {
                case "REGISTER":
                case "CHECKIN":
                case "ACTIVITY":
                    return ruleValue.has("points") ? ruleValue.get("points").asLong() : 0L;

                case "PURCHASE":
                    long minAmount = ruleValue.has("minAmount") ? ruleValue.get("minAmount").asLong() : 0L;
                    long amount = request.getAmount() != null ? request.getAmount() : 0L;
                    if (amount < minAmount) {
                        return 0L;
                    }
                    long rate = ruleValue.has("rate") ? ruleValue.get("rate").asLong() : 1L;
                    return amount * rate;

                default:
                    return 0L;
            }
        } catch (Exception e) {
            log.error("Failed to parse rule value for rule: {}", rule.getRuleCode(), e);
            return 0L;
        }
    }

    @Override
    public List<PointsRule> getActiveRules() {
        return loadRules();
    }

    @Override
    public PointsRule getActiveRule(String ruleCode) {
        return loadRules().stream()
                .filter(r -> r.getRuleCode().equals(ruleCode) && r.getStatus() == 1)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<PointsRule> loadRules() {
        // Try cache first
        RBucket<List<PointsRule>> bucket = redissonClient.getBucket(RULES_CACHE_KEY);
        if (bucket.isExists()) {
            List<PointsRule> cached = bucket.get();
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        // Load from DB
        LambdaQueryWrapper<PointsRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsRule::getStatus, 1)
               .orderByDesc(PointsRule::getPriority);
        List<PointsRule> rules = ruleMapper.selectList(wrapper);

        // Fallback: if no active rules found, load latest versions of all rules
        if (rules == null || rules.isEmpty()) {
            log.warn("No active rules found in DB, attempting fallback to latest rule versions");
            LambdaQueryWrapper<PointsRule> fallbackWrapper = new LambdaQueryWrapper<>();
            fallbackWrapper.orderByDesc(PointsRule::getVersion);
            List<PointsRule> allRules = ruleMapper.selectList(fallbackWrapper);
            if (allRules != null && !allRules.isEmpty()) {
                rules = new java.util.ArrayList<>(allRules.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                PointsRule::getRuleCode,
                                r -> r,
                                (r1, r2) -> r1.getVersion() >= r2.getVersion() ? r1 : r2
                        ))
                        .values());
                log.warn("Fallback loaded {} rules from {} total", rules.size(), allRules.size());
            }
        }

        // Cache for 10 minutes
        if (rules != null && !rules.isEmpty()) {
            bucket.set(rules, Duration.ofMinutes(10));
        }

        return rules;
    }
}
