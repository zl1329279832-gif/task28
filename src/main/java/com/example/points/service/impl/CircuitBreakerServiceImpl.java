package com.example.points.service.impl;

import com.example.points.common.BusinessException;
import com.example.points.entity.CircuitBreaker;
import com.example.points.enums.CircuitBreakerStatus;
import com.example.points.mapper.CircuitBreakerMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.CircuitBreakerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerServiceImpl implements CircuitBreakerService {

    private final CircuitBreakerMapper circuitBreakerMapper;
    private final AuditLogService auditLogService;

    @Override
    public CircuitBreaker getByPoolId(Long poolId) {
        return circuitBreakerMapper.selectByPoolId(poolId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualClose(Long poolId, String operator) {
        CircuitBreaker cb = getByPoolId(poolId);
        if (cb == null) {
            throw new BusinessException("熔断器不存在: poolId=" + poolId);
        }

        String previousStatus = cb.getStatus();

        int rows = circuitBreakerMapper.casTransition(cb.getId(), previousStatus,
                CircuitBreakerStatus.CLOSED.name());
        if (rows == 0) {
            throw new BusinessException("熔断器状态变更失败");
        }

        // Reset counters
        cb.setStatus(CircuitBreakerStatus.CLOSED.name());
        cb.setFailureCount(0);
        cb.setHalfOpenCount(0);
        cb.setUpdateTime(LocalDateTime.now());
        circuitBreakerMapper.updateById(cb);

        auditLogService.log("CIRCUIT_BREAKER", "MANUAL_CLOSE", String.valueOf(poolId),
                "BUDGET_POOL", previousStatus, "CLOSED", operator, null);

        log.info("Circuit breaker manually closed: poolId={}, operator={}", poolId, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualOpen(Long poolId, String operator) {
        CircuitBreaker cb = getByPoolId(poolId);
        if (cb == null) {
            throw new BusinessException("熔断器不存在: poolId=" + poolId);
        }

        String previousStatus = cb.getStatus();

        int rows = circuitBreakerMapper.casTransition(cb.getId(), previousStatus,
                CircuitBreakerStatus.OPEN.name());
        if (rows == 0) {
            throw new BusinessException("熔断器状态变更失败");
        }

        auditLogService.log("CIRCUIT_BREAKER", "MANUAL_OPEN", String.valueOf(poolId),
                "BUDGET_POOL", previousStatus, "OPEN", operator, null);

        log.info("Circuit breaker manually opened: poolId={}, operator={}", poolId, operator);
    }

    @Override
    public void processRecoveryChecks() {
        // Find OPEN breakers past cooldown
        List<CircuitBreaker> openBreakers = circuitBreakerMapper.findOpenBreakersReadyForRecovery();
        for (CircuitBreaker cb : openBreakers) {
            try {
                int rows = circuitBreakerMapper.casTransition(cb.getId(),
                        CircuitBreakerStatus.OPEN.name(), CircuitBreakerStatus.HALF_OPEN.name());
                if (rows > 0) {
                    auditLogService.log("CIRCUIT_BREAKER", "AUTO_RECOVERY", String.valueOf(cb.getPoolId()),
                            "BUDGET_POOL", "OPEN", "HALF_OPEN", "SYSTEM", null);
                    log.info("Circuit breaker OPEN -> HALF_OPEN: poolId={}", cb.getPoolId());
                }
            } catch (Exception e) {
                log.error("Error processing recovery for poolId={}", cb.getPoolId(), e);
            }
        }

        // Check HALF_OPEN breakers for closure
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CircuitBreaker> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(CircuitBreaker::getStatus, CircuitBreakerStatus.HALF_OPEN.name());
        List<CircuitBreaker> halfOpenBreakers = circuitBreakerMapper.selectList(wrapper);

        for (CircuitBreaker cb : halfOpenBreakers) {
            try {
                if (cb.getHalfOpenCount() >= cb.getMaxTestRequests()) {
                    int rows = circuitBreakerMapper.closeFromHalfOpen(cb.getPoolId());
                    if (rows > 0) {
                        auditLogService.log("CIRCUIT_BREAKER", "AUTO_CLOSE", String.valueOf(cb.getPoolId()),
                                "BUDGET_POOL", "HALF_OPEN", "CLOSED", "SYSTEM", null);
                        log.info("Circuit breaker HALF_OPEN -> CLOSED: poolId={}", cb.getPoolId());
                    }
                }
            } catch (Exception e) {
                log.error("Error closing half-open breaker for poolId={}", cb.getPoolId(), e);
            }
        }
    }

    @Override
    public void recordHalfOpenSuccess(Long poolId) {
        circuitBreakerMapper.incrementHalfOpenCount(poolId);
    }

    @Override
    public void recordHalfOpenFailure(Long poolId) {
        int rows = circuitBreakerMapper.reopenFromHalfOpen(poolId);
        if (rows > 0) {
            log.info("Circuit breaker HALF_OPEN -> OPEN (failure): poolId={}", poolId);
        }
    }
}
