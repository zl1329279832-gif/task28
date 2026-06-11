package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.dto.FreezeRequest;
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
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
    private final PointsFreezeService pointsFreezeService;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

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

        List<String> breachedTypes = new ArrayList<>();

        for (RiskControlConfig config : configs) {
            try {
                boolean breached = evaluateRule(config, memberId, budgetPoolId, flowId, points);
                if (breached) {
                    breachedTypes.add(config.getRuleType());
                }
            } catch (Exception e) {
                log.error("Error evaluating risk rule {} for pool {}", config.getRuleType(), budgetPoolId, e);
            }
        }

        if (!breachedTypes.isEmpty()) {
            // Trip the circuit breaker
            tripCircuitBreaker(budgetPoolId, configs);

            // If BLACKLIST_HIT was detected, freeze the points AND budget
            if (breachedTypes.contains("BLACKLIST_HIT")) {
                try {
                    triggerFreezeForFlow(memberId, budgetPoolId, flowId, points, "BLACKLIST_HIT");
                } catch (Exception e) {
                    log.error("Failed to auto-freeze after BLACKLIST_HIT: memberId={}, flowId={}",
                            memberId, flowId, e);
                }
            }
        }
    }

    @Override
    public List<String> evaluateInTransaction(Long memberId, Long budgetPoolId, Long flowId, long points) {
        if (budgetPoolId == null) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<RiskControlConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RiskControlConfig::getPoolId, budgetPoolId)
               .eq(RiskControlConfig::getEnabled, 1);
        List<RiskControlConfig> configs = riskControlConfigMapper.selectList(wrapper);

        List<String> breachedTypes = new ArrayList<>();
        for (RiskControlConfig config : configs) {
            try {
                boolean breached = evaluateRule(config, memberId, budgetPoolId, flowId, points);
                if (breached) {
                    breachedTypes.add(config.getRuleType());
                }
            } catch (Exception e) {
                log.error("Error evaluating risk rule {} in-transaction for pool {}",
                        config.getRuleType(), budgetPoolId, e);
            }
        }
        return breachedTypes;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void triggerFreezeForFlow(Long memberId, Long budgetPoolId, Long flowId,
                                      long points, String freezeType) {
        String freezeNo = "RISK_AUTO_" + System.currentTimeMillis() + "_" + flowId;
        FreezeRequest freezeRequest = new FreezeRequest();
        freezeRequest.setMemberId(memberId);
        freezeRequest.setPoints(points);
        freezeRequest.setFreezeNo(freezeNo);
        freezeRequest.setBizOrderNo("RISK_FLOW_" + flowId);
        freezeRequest.setReason("风控自动冻结: " + freezeType + ", flowId=" + flowId);
        freezeRequest.setFreezeHours(72);

        pointsFreezeService.freezeWithBudget(freezeRequest, budgetPoolId);

        auditLogService.log("RISK_CONTROL", "AUTO_FREEZE", String.valueOf(flowId),
                "POINTS_FLOW", null, String.valueOf(points), "SYSTEM", null);

        log.info("Auto-freeze triggered: memberId={}, flowId={}, points={}, freezeType={}",
                memberId, flowId, points, freezeType);
    }

    private boolean evaluateRule(RiskControlConfig config, Long memberId, Long poolId,
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
                return false;
        }
    }

    private boolean evaluateHighFrequency(RiskControlConfig config, Long memberId,
                                            Long poolId, Long flowId) {
        try {
            JsonNode threshold = objectMapper.readTree(config.getThresholdValue());
            int maxClaims = threshold.get("maxClaims").asInt();
            int windowMinutes = threshold.get("windowMinutes").asInt();

            // Use Redis atomic counter for real-time accuracy
            String counterKey = "risk:freq:" + memberId + ":" + poolId;
            RAtomicLong counter = redissonClient.getAtomicLong(counterKey);

            long currentCount = counter.incrementAndGet();

            // Set expiry on first increment
            if (currentCount == 1) {
                counter.expire(Duration.ofMinutes(windowMinutes));
            }

            if (currentCount > maxClaims) {
                createRiskEvent(poolId, memberId, "HIGH_FREQUENCY", flowId,
                        String.format("{\"claimCount\":%d,\"maxClaims\":%d,\"windowMinutes\":%d}",
                                currentCount, maxClaims, windowMinutes));
                log.warn("HIGH_FREQUENCY risk breached: memberId={}, claims={}, max={}",
                        memberId, currentCount, maxClaims);
                return true;
            }
        } catch (Exception e) {
            log.error("Error evaluating HIGH_FREQUENCY rule", e);
        }
        return false;
    }

    private boolean evaluateAbnormalRefund(RiskControlConfig config, Long memberId,
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
                    createRiskEvent(poolId, memberId, "ABNORMAL_REFUND", flowId,
                            String.format("{\"refundCount\":%d,\"issuanceCount\":%d,\"ratePercent\":%d}",
                                    refundCount, issuanceCount, refundRatePercent));
                    log.warn("ABNORMAL_REFUND risk breached: memberId={}, rate={}%", memberId, refundRatePercent);
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Error evaluating ABNORMAL_REFUND rule", e);
        }
        return false;
    }

    private boolean evaluateBlacklistHit(Long memberId, Long poolId, Long flowId) {
        if (blacklistService.isBlacklisted(memberId)) {
            createRiskEvent(poolId, memberId, "BLACKLIST_HIT", flowId,
                    "{\"memberId\":" + memberId + "}");
            log.warn("BLACKLIST_HIT risk breached: memberId={}", memberId);
            return true;
        }
        return false;
    }

    private boolean evaluateBudgetExhaustion(RiskControlConfig config, Long poolId, Long flowId) {
        try {
            JsonNode threshold = objectMapper.readTree(config.getThresholdValue());
            int usageThresholdPercent = threshold.get("usageThresholdPercent").asInt();

            BudgetPool pool = budgetPoolService.getPool(poolId);
            if (pool.getTotalBudget() > 0) {
                int usagePercent = (int) ((pool.getUsedBudget() * 100) / pool.getTotalBudget());
                if (usagePercent >= usageThresholdPercent) {
                    createRiskEvent(poolId, null, "BUDGET_EXHAUSTION", flowId,
                            String.format("{\"usedBudget\":%d,\"totalBudget\":%d,\"usagePercent\":%d}",
                                    pool.getUsedBudget(), pool.getTotalBudget(), usagePercent));
                    log.warn("BUDGET_EXHAUSTION risk breached: poolId={}, usage={}% ", poolId, usagePercent);
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Error evaluating BUDGET_EXHAUSTION rule", e);
        }
        return false;
    }

    private void createRiskEvent(Long poolId, Long memberId, String eventType,
                                  Long flowId, String detail) {
        String idempotentKey = flowId + "_" + eventType;

        RiskEvent event = RiskEvent.builder()
                .poolId(poolId)
                .memberId(memberId)
                .eventType(eventType)
                .flowId(flowId)
                .detail(detail)
                .status(RiskEventStatus.OPEN.getCode())
                .idempotentKey(idempotentKey)
                .createTime(LocalDateTime.now())
                .build();

        int rows = riskEventMapper.insertIgnoreDuplicate(event);
        if (rows == 0) {
            log.info("Duplicate risk event suppressed: flowId={}, type={}", flowId, eventType);
        }
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
                    .budgetReleased(0)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            circuitBreakerMapper.insert(cb);
            log.info("Circuit breaker created and tripped: poolId={}", poolId);
        } else {
            // Post-issuance: budget was already reserved (not released)
            circuitBreakerMapper.tripBreakerWithBudgetFlag(poolId, 0);
            log.info("Circuit breaker tripped: poolId={}", poolId);
        }
    }
}
