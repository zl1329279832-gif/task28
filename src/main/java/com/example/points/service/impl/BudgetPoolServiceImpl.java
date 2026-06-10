package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.BudgetPoolCreateRequest;
import com.example.points.dto.BudgetPoolUpdateRequest;
import com.example.points.entity.BudgetPool;
import com.example.points.entity.BudgetUsageLog;
import com.example.points.enums.BudgetPoolStatus;
import com.example.points.enums.BudgetUsageType;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.mapper.BudgetUsageLogMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.BudgetPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetPoolServiceImpl implements BudgetPoolService {

    private final BudgetPoolMapper budgetPoolMapper;
    private final BudgetUsageLogMapper budgetUsageLogMapper;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BudgetPool createPool(BudgetPoolCreateRequest request) {
        // Check unique activity_code
        BudgetPool existing = budgetPoolMapper.selectByActivityCode(request.getActivityCode());
        if (existing != null) {
            throw new BusinessException("活动代码已存在: " + request.getActivityCode());
        }

        BudgetPool pool = BudgetPool.builder()
                .activityCode(request.getActivityCode())
                .activityName(request.getActivityName())
                .totalBudget(request.getTotalBudget())
                .usedBudget(0L)
                .frozenBudget(0L)
                .applicableLevels(request.getApplicableLevels())
                .dailyLimit(request.getDailyLimit() != null ? request.getDailyLimit() : 0L)
                .monthlyLimit(request.getMonthlyLimit() != null ? request.getMonthlyLimit() : 0L)
                .riskThreshold(request.getRiskThreshold() != null ? request.getRiskThreshold() : 0L)
                .circuitBreakRate(request.getCircuitBreakRate() != null ? request.getCircuitBreakRate() : 90)
                .status(BudgetPoolStatus.ENABLED.getCode())
                .effectiveStart(request.getEffectiveStart())
                .effectiveEnd(request.getEffectiveEnd())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        budgetPoolMapper.insert(pool);

        auditLogService.log("BUDGET_POOL", "CREATE", String.valueOf(pool.getId()),
                "BUDGET_POOL", null, pool.toString(), request.getOperator(), null);

        log.info("Budget pool created: activityCode={}, totalBudget={}",
                request.getActivityCode(), request.getTotalBudget());
        return pool;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BudgetPool updatePool(BudgetPoolUpdateRequest request) {
        BudgetPool pool = budgetPoolMapper.selectById(request.getPoolId());
        if (pool == null) {
            throw new BusinessException("预算池不存在");
        }

        String beforeValue = pool.toString();

        if (request.getActivityName() != null) pool.setActivityName(request.getActivityName());
        if (request.getTotalBudget() != null) pool.setTotalBudget(request.getTotalBudget());
        if (request.getApplicableLevels() != null) pool.setApplicableLevels(request.getApplicableLevels());
        if (request.getDailyLimit() != null) pool.setDailyLimit(request.getDailyLimit());
        if (request.getMonthlyLimit() != null) pool.setMonthlyLimit(request.getMonthlyLimit());
        if (request.getRiskThreshold() != null) pool.setRiskThreshold(request.getRiskThreshold());
        if (request.getCircuitBreakRate() != null) pool.setCircuitBreakRate(request.getCircuitBreakRate());
        if (request.getStatus() != null) pool.setStatus(request.getStatus());
        if (request.getEffectiveStart() != null) pool.setEffectiveStart(request.getEffectiveStart());
        if (request.getEffectiveEnd() != null) pool.setEffectiveEnd(request.getEffectiveEnd());
        pool.setUpdateTime(LocalDateTime.now());

        budgetPoolMapper.updateById(pool);

        auditLogService.log("BUDGET_POOL", "UPDATE", String.valueOf(pool.getId()),
                "BUDGET_POOL", beforeValue, pool.toString(), request.getOperator(), null);

        return pool;
    }

    @Override
    public void updatePoolStatus(Long poolId, Integer status, String operator) {
        BudgetPool pool = budgetPoolMapper.selectById(poolId);
        if (pool == null) {
            throw new BusinessException("预算池不存在");
        }
        int oldStatus = pool.getStatus();
        budgetPoolMapper.updateStatus(poolId, status);

        auditLogService.log("BUDGET_POOL", "STATUS_CHANGE", String.valueOf(poolId),
                "BUDGET_POOL", String.valueOf(oldStatus), String.valueOf(status), operator, null);
    }

    @Override
    public BudgetPool getActivePool(String activityCode) {
        if (activityCode == null || activityCode.isEmpty()) {
            return null;
        }
        BudgetPool pool = budgetPoolMapper.selectByActivityCode(activityCode);
        if (pool == null) {
            return null;
        }
        if (pool.getStatus() != BudgetPoolStatus.ENABLED.getCode()) {
            return null;
        }
        // Check effective time range
        LocalDateTime now = LocalDateTime.now();
        if (pool.getEffectiveStart() != null && now.isBefore(pool.getEffectiveStart())) {
            return null;
        }
        if (pool.getEffectiveEnd() != null && now.isAfter(pool.getEffectiveEnd())) {
            return null;
        }
        return pool;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void occupyBudget(Long poolId, Long memberId, Long points,
                             String eventId, String bizOrderNo) {
        String lockKey = "lock:budget:pool:" + poolId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("预算池繁忙，请稍后重试");
            }

            // Re-read pool inside lock
            BudgetPool pool = budgetPoolMapper.selectById(poolId);
            if (pool == null || pool.getStatus() != BudgetPoolStatus.ENABLED.getCode()) {
                throw new BusinessException("预算池不可用");
            }

            // Check effective time range
            LocalDateTime now = LocalDateTime.now();
            if (pool.getEffectiveStart() != null && now.isBefore(pool.getEffectiveStart())) {
                throw new BusinessException("预算池尚未生效");
            }
            if (pool.getEffectiveEnd() != null && now.isAfter(pool.getEffectiveEnd())) {
                throw new BusinessException("预算池已过期");
            }

            // Check daily limit
            if (pool.getDailyLimit() != null && pool.getDailyLimit() > 0) {
                Long dailyUsed = budgetUsageLogMapper.sumDailyOccupied(poolId);
                if (dailyUsed + points > pool.getDailyLimit()) {
                    throw new BusinessException("预算池日发放额度已达上限");
                }
            }

            // Check monthly limit
            if (pool.getMonthlyLimit() != null && pool.getMonthlyLimit() > 0) {
                Long monthlyUsed = budgetUsageLogMapper.sumMonthlyOccupied(poolId);
                if (monthlyUsed + points > pool.getMonthlyLimit()) {
                    throw new BusinessException("预算池月发放额度已达上限");
                }
            }

            // Atomic occupy
            int rows = budgetPoolMapper.occupyBudget(poolId, points);
            if (rows == 0) {
                throw new BusinessException("预算池额度不足");
            }

            // Save usage log
            BudgetUsageLog usageLog = BudgetUsageLog.builder()
                    .poolId(poolId)
                    .memberId(memberId)
                    .eventId("BUDGET_OCCUPY_" + eventId)
                    .usageType(BudgetUsageType.OCCUPY.getCode())
                    .points(points)
                    .bizOrderNo(bizOrderNo)
                    .remark("占用预算额度")
                    .createTime(LocalDateTime.now())
                    .build();
            budgetUsageLogMapper.insert(usageLog);

            // Check circuit break
            if (checkCircuitBreak(poolId)) {
                triggerCircuitBreak(poolId, "使用率达到熔断阈值");
            }

            log.info("Budget occupied: poolId={}, memberId={}, points={}, eventId={}",
                    poolId, memberId, points, eventId);
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
    public void releaseBudget(Long poolId, Long memberId, Long points,
                              String eventId, String bizOrderNo) {
        String lockKey = "lock:budget:pool:" + poolId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("预算池繁忙，请稍后重试");
            }

            int rows = budgetPoolMapper.releaseBudget(poolId, points);
            if (rows == 0) {
                log.warn("Release budget failed: poolId={}, points={}", poolId, points);
                return;
            }

            BudgetUsageLog usageLog = BudgetUsageLog.builder()
                    .poolId(poolId)
                    .memberId(memberId)
                    .eventId("BUDGET_REFUND_" + eventId)
                    .usageType(BudgetUsageType.REFUND.getCode())
                    .points(points)
                    .bizOrderNo(bizOrderNo)
                    .remark("退款回补预算额度")
                    .createTime(LocalDateTime.now())
                    .build();
            budgetUsageLogMapper.insert(usageLog);

            auditLogService.log("BUDGET_POOL", "RELEASE", String.valueOf(poolId),
                    "BUDGET_POOL", String.valueOf(points), "REFUND",
                    "SYSTEM", null);

            log.info("Budget released: poolId={}, memberId={}, points={}, eventId={}",
                    poolId, memberId, points, eventId);
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
    public boolean checkCircuitBreak(Long poolId) {
        BudgetPool pool = budgetPoolMapper.selectById(poolId);
        if (pool == null || pool.getTotalBudget() == 0) {
            return false;
        }
        long usedPercent = pool.getUsedBudget() * 100 / pool.getTotalBudget();
        return usedPercent >= pool.getCircuitBreakRate();
    }

    @Override
    public void triggerCircuitBreak(Long poolId, String reason) {
        budgetPoolMapper.updateStatus(poolId, BudgetPoolStatus.CIRCUIT_BROKEN.getCode());

        auditLogService.log("BUDGET_POOL", "CIRCUIT_BREAK", String.valueOf(poolId),
                "BUDGET_POOL", null, reason, "SYSTEM", null);

        log.warn("Budget pool circuit broken: poolId={}, reason={}", poolId, reason);
    }

    @Override
    public void recoverPool(Long poolId, String operator) {
        BudgetPool pool = budgetPoolMapper.selectById(poolId);
        if (pool == null) {
            throw new BusinessException("预算池不存在");
        }
        if (pool.getStatus() != BudgetPoolStatus.CIRCUIT_BROKEN.getCode()) {
            throw new BusinessException("预算池未处于熔断状态");
        }

        budgetPoolMapper.updateStatus(poolId, BudgetPoolStatus.ENABLED.getCode());

        auditLogService.log("BUDGET_POOL", "RECOVER", String.valueOf(poolId),
                "BUDGET_POOL", "CIRCUIT_BROKEN", "ENABLED", operator, null);

        log.info("Budget pool recovered: poolId={}, operator={}", poolId, operator);
    }

    @Override
    public List<BudgetPool> listPools(Integer status) {
        LambdaQueryWrapper<BudgetPool> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(BudgetPool::getStatus, status);
        }
        wrapper.orderByDesc(BudgetPool::getCreateTime);
        return budgetPoolMapper.selectList(wrapper);
    }
}
