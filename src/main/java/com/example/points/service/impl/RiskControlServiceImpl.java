package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.entity.*;
import com.example.points.enums.CircuitBreakerStatus;
import com.example.points.enums.RiskEventStatus;
import com.example.points.mapper.CircuitBreakerMapper;
import com.example.points.mapper.RiskControlConfigMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiskControlServiceImpl implements RiskControlService {

    private final CircuitBreakerMapper circuitBreakerMapper;
    private final CircuitBreakerService circuitBreakerService;
    private final RiskControlConfigMapper riskControlConfigMapper;
    private final RiskEventMapper riskEventMapper;
    private final BudgetPoolService budgetPoolService;
    private final BlacklistService blacklistService;
    private final ObjectMapper objectMapper;
    private final RiskFreezeReviewService riskFreezeReviewService;
    private final AuditLogService auditLogService;

    @Override
    public boolean isCircuitBreakerAllowing(Long budgetPoolId) {
        if (budgetPoolId == null) {
            return true;
        }

        CircuitBreaker cb = circuitBreakerMapper.selectByPoolId(budgetPoolId);
        if (cb == null) {
            return true;
        }

        String status = cb.getStatus();
        if (CircuitBreakerStatus.CLOSED.name().equals(status)) {
            return true;
        }
        if (CircuitBreakerStatus.OPEN.name().equals(status)) {
            return false;
        }
        if (CircuitBreakerStatus.HALF_OPEN.name().equals(status)) {
            return cb.getHalfOpenCount() < cb.getMaxTestRequests();
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void evaluatePostIssuance(Long memberId, Long budgetPoolId, Long flowId, long points) {
        if (budgetPoolId == null) {
            return;
        }

        // Load risk control configs for this pool
        LambdaQueryWrapper<RiskControlConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RiskControlConfig::getPoolId, budgetPoolId)
               .eq(RiskControlConfig::getEnabled, 1);
        List<RiskControlConfig> configs = riskControlConfigMapper.selectList(wrapper);

        List<Long> breachedRiskEventIds = new ArrayList<>();
        String breachedType = null;

        for (RiskControlConfig config : configs) {
            try {
                Long riskEventId = evaluateRule(config, memberId, budgetPoolId, flowId, points);
                if (riskEventId != null) {
                    breachedRiskEventIds.add(riskEventId);
                    if (breachedType == null) {
                        breachedType = config.getRuleType();
                    }
                }
            } catch (Exception e) {
                log.error("Error evaluating risk rule {} for pool {}", config.getRuleType(), budgetPoolId, e);
            }
        }

        if (!breachedRiskEventIds.isEmpty()) {
            // Trip the circuit breaker
            tripCircuitBreaker(budgetPoolId, configs);

            // Auto-freeze the issued points and create review order
            try {
                int expireHours = configs.isEmpty() ? 72 :
                        configs.get(0).getCooldownMinutes() > 0 ? configs.get(0).getCooldownMinutes() * 2 : 72;
                riskFreezeReviewService.createRiskFreeze(
                        memberId, budgetPoolId, breachedRiskEventIds.get(0),
                        breachedType, points, Math.max(expireHours, 24));

                auditLogService.log("RISK_CONTROL", "AUTO_FREEZE", String.valueOf(memberId),
                        "MEMBER", String.valueOf(points), "FROZEN",
                        "SYSTEM", "风控自动冻结，规则: " + breachedType);

                log.info("Risk auto-freeze created: memberId={}, points={}, ruleType={}",
                        memberId, points, breachedType);
            } catch (Exception e) {
                log.error("Failed to create risk freeze for memberId={}, poolId={}", memberId, budgetPoolId, e);
            }
        }
    }

    /**
     * Evaluates a single risk rule. Returns the riskEventId if breached, null otherwise.
     */
    private Long evaluateRule(RiskControlConfig config, Long memberId, Long poolId,
                              Long flowId, long points) {
        String ruleType = config.getRuleType();
        switch (ruleType) {
            case "HIGH_FREQUENCY":
                return evaluateHighFrequency(config, memberId, poolId, flowId);
            case "ABNORMAL_REFUND":
                return evaluateAbnormalRefund(config, memberId, poolId, flowId);
            case "BLACKLIST_HIT":
                return evaluateBlacklistHit(memberId, poolId, flowId);
            case "BUDGET_EXHAUSTION":
                return evaluateBudgetExhaustion(config, poolId, flowId);
            default:
                log.warn("Unknown risk rule type: {}", ruleType);
                return null;
        }
    }

    private Long evaluateHighFrequency(RiskControlConfig config, Long memberId,
                                        Long poolId, Long flowId) {
        try {
            JsonNode threshold = objectMapper.readTree(config.getThresholdValue());
            int maxClaims = threshold.get("maxClaims").asInt();
            int windowMinutes = threshold.get("windowMinutes").asInt();

            LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
            int claimCount = riskEventMapper.countMemberClaimsSince(memberId, since);

            if (claimCount > maxClaims) {
                Long riskEventId = createRiskEvent(poolId, memberId, "HIGH_FREQUENCY", flowId,
                        String.format("{\"claimCount\":%d,\"maxClaims\":%d,\"windowMinutes\":%d}",
                                claimCount, maxClaims, windowMinutes));
                log.warn("HIGH_FREQUENCY risk breached: memberId={}, claims={}, max={}",
                        memberId, claimCount, maxClaims);
                return riskEventId;
            }
        } catch (Exception e) {
            log.error("Error evaluating HIGH_FREQUENCY rule", e);
        }
        return null;
    }

    private Long evaluateAbnormalRefund(RiskControlConfig config, Long memberId,
                                         Long poolId, Long flowId) {
        try {
            JsonNode threshold = objectMapper.readTree(config.getThresholdValue());
            int maxRefundRatePercent = threshold.get("maxRefundRatePercent").asInt();
            int windowHours = threshold.get("windowHours").asInt();

            LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
            int refundCount = riskEventMapper.countMemberRefundsSince(memberId, since);
            int issuanceCount = riskEventMapper.countMemberIssuancesSince(memberId, since);

            if (issuanceCount > 0) {
                int refundRatePercent = (refundCount * 100) / issuanceCount;
                if (refundRatePercent > maxRefundRatePercent) {
                    Long riskEventId = createRiskEvent(poolId, memberId, "ABNORMAL_REFUND", flowId,
                            String.format("{\"refundCount\":%d,\"issuanceCount\":%d,\"ratePercent\":%d}",
                                    refundCount, issuanceCount, refundRatePercent));
                    log.warn("ABNORMAL_REFUND risk breached: memberId={}, rate={}%", memberId, refundRatePercent);
                    return riskEventId;
                }
            }
        } catch (Exception e) {
            log.error("Error evaluating ABNORMAL_REFUND rule", e);
        }
        return null;
    }

    private Long evaluateBlacklistHit(Long memberId, Long poolId, Long flowId) {
        if (blacklistService.isBlacklisted(memberId)) {
            Long riskEventId = createRiskEvent(poolId, memberId, "BLACKLIST_HIT", flowId,
                    "{\"memberId\":" + memberId + "}");
            log.warn("BLACKLIST_HIT risk breached: memberId={}", memberId);
            return riskEventId;
        }
        return null;
    }

    private Long evaluateBudgetExhaustion(RiskControlConfig config, Long poolId, Long flowId) {
        try {
            JsonNode threshold = objectMapper.readTree(config.getThresholdValue());
            int usageThresholdPercent = threshold.get("usageThresholdPercent").asInt();

            BudgetPool pool = budgetPoolService.getPool(poolId);
            if (pool.getTotalBudget() > 0) {
                int usagePercent = (int) ((pool.getUsedBudget() * 100) / pool.getTotalBudget());
                if (usagePercent >= usageThresholdPercent) {
                    Long riskEventId = createRiskEvent(poolId, null, "BUDGET_EXHAUSTION", flowId,
                            String.format("{\"usedBudget\":%d,\"totalBudget\":%d,\"usagePercent\":%d}",
                                    pool.getUsedBudget(), pool.getTotalBudget(), usagePercent));
                    log.warn("BUDGET_EXHAUSTION risk breached: poolId={}, usage={}% ", poolId, usagePercent);
                    return riskEventId;
                }
            }
        } catch (Exception e) {
            log.error("Error evaluating BUDGET_EXHAUSTION rule", e);
        }
        return null;
    }

    private Long createRiskEvent(Long poolId, Long memberId, String eventType,
                                  Long flowId, String detail) {
        RiskEvent event = RiskEvent.builder()
                .poolId(poolId)
                .memberId(memberId)
                .eventType(eventType)
                .flowId(flowId)
                .detail(detail)
                .status(RiskEventStatus.OPEN.getCode())
                .createTime(LocalDateTime.now())
                .build();
        riskEventMapper.insert(event);
        return event.getId();
    }

    private void tripCircuitBreaker(Long poolId, List<RiskControlConfig> configs) {
        // Ensure circuit breaker record exists
        CircuitBreaker cb = circuitBreakerMapper.selectByPoolId(poolId);
        if (cb == null) {
            // Use cooldown from first config
            int cooldown = configs.isEmpty() ? 30 : configs.get(0).getCooldownMinutes();
            int maxTest = configs.isEmpty() ? 10 : configs.get(0).getMaxTestRequests();

            cb = CircuitBreaker.builder()
                    .poolId(poolId)
                    .status(CircuitBreakerStatus.OPEN.name())
                    .failureCount(1)
                    .lastFailureTime(LocalDateTime.now())
                    .lastStateChange(LocalDateTime.now())
                    .cooldownMinutes(cooldown)
                    .halfOpenCount(0)
                    .maxTestRequests(maxTest)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            circuitBreakerMapper.insert(cb);

            auditLogService.log("CIRCUIT_BREAKER", "AUTO_TRIP", String.valueOf(poolId),
                    "BUDGET_POOL", null, "OPEN", "SYSTEM", null);

            log.info("Circuit breaker created and tripped: poolId={}", poolId);
        } else {
            String previousStatus = cb.getStatus();
            circuitBreakerMapper.tripBreaker(poolId);

            auditLogService.log("CIRCUIT_BREAKER", "AUTO_TRIP", String.valueOf(poolId),
                    "BUDGET_POOL", previousStatus, "OPEN", "SYSTEM", null);

            log.info("Circuit breaker tripped: poolId={}", poolId);
        }
    }
}
