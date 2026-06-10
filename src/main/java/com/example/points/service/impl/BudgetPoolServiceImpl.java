package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.entity.BudgetPool;
import com.example.points.enums.BudgetPoolStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.BudgetPoolService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetPoolServiceImpl implements BudgetPoolService {

    private final BudgetPoolMapper budgetPoolMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BudgetPool createPool(BudgetPool pool) {
        pool.setStatus(BudgetPoolStatus.ACTIVE.getCode());
        pool.setUsedBudget(0L);
        pool.setDailyUsed(0L);
        pool.setMonthlyUsed(0L);
        pool.setCreateTime(LocalDateTime.now());
        pool.setUpdateTime(LocalDateTime.now());
        budgetPoolMapper.insert(pool);

        auditLogService.log("BUDGET_POOL", "CREATE", String.valueOf(pool.getId()),
                "BUDGET_POOL", null, String.valueOf(pool.getTotalBudget()),
                "SYSTEM", null);

        log.info("Budget pool created: id={}, name={}, totalBudget={}",
                pool.getId(), pool.getPoolName(), pool.getTotalBudget());
        return pool;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BudgetPool updatePool(Long poolId, BudgetPool update) {
        BudgetPool existing = getPool(poolId);
        String beforeValue = String.valueOf(existing.getTotalBudget());

        if (update.getPoolName() != null) {
            existing.setPoolName(update.getPoolName());
        }
        if (update.getTotalBudget() != null) {
            existing.setTotalBudget(update.getTotalBudget());
        }
        if (update.getDailyCap() != null) {
            existing.setDailyCap(update.getDailyCap());
        }
        if (update.getMonthlyCap() != null) {
            existing.setMonthlyCap(update.getMonthlyCap());
        }
        if (update.getApplicableLevels() != null) {
            existing.setApplicableLevels(update.getApplicableLevels());
        }
        if (update.getStartTime() != null) {
            existing.setStartTime(update.getStartTime());
        }
        if (update.getEndTime() != null) {
            existing.setEndTime(update.getEndTime());
        }
        existing.setUpdateTime(LocalDateTime.now());
        budgetPoolMapper.updateById(existing);

        auditLogService.log("BUDGET_POOL", "UPDATE", String.valueOf(poolId),
                "BUDGET_POOL", beforeValue, String.valueOf(existing.getTotalBudget()),
                "SYSTEM", null);

        log.info("Budget pool updated: id={}", poolId);
        return existing;
    }

    @Override
    public BudgetPool getPool(Long poolId) {
        BudgetPool pool = budgetPoolMapper.selectById(poolId);
        if (pool == null) {
            throw new BusinessException("预算池不存在: " + poolId);
        }
        return pool;
    }

    @Override
    public List<BudgetPool> listActivePools() {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<BudgetPool> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BudgetPool::getStatus, BudgetPoolStatus.ACTIVE.getCode())
               .le(BudgetPool::getStartTime, now)
               .ge(BudgetPool::getEndTime, now);
        return budgetPoolMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void suspendPool(Long poolId, String operator) {
        BudgetPool pool = getPool(poolId);
        pool.setStatus(BudgetPoolStatus.SUSPENDED.getCode());
        pool.setUpdateTime(LocalDateTime.now());
        budgetPoolMapper.updateById(pool);

        auditLogService.log("BUDGET_POOL", "SUSPEND", String.valueOf(poolId),
                "BUDGET_POOL", "ACTIVE", "SUSPENDED", operator, null);

        log.info("Budget pool suspended: id={}, operator={}", poolId, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reactivatePool(Long poolId, String operator) {
        BudgetPool pool = getPool(poolId);
        pool.setStatus(BudgetPoolStatus.ACTIVE.getCode());
        pool.setUpdateTime(LocalDateTime.now());
        budgetPoolMapper.updateById(pool);

        auditLogService.log("BUDGET_POOL", "REACTIVATE", String.valueOf(poolId),
                "BUDGET_POOL", "SUSPENDED", "ACTIVE", operator, null);

        log.info("Budget pool reactivated: id={}, operator={}", poolId, operator);
    }

    @Override
    public void reserveBudget(Long poolId, long points) {
        int rows = budgetPoolMapper.reserveBudget(poolId, points);
        if (rows == 0) {
            throw new BusinessException("预算池额度不足或已超限");
        }
        log.debug("Budget reserved: poolId={}, points={}", poolId, points);
    }

    @Override
    public void releaseBudget(Long poolId, long points) {
        int rows = budgetPoolMapper.releaseBudget(poolId, points);
        if (rows == 0) {
            log.warn("Budget release returned 0 rows: poolId={}, points={}", poolId, points);
        }
        log.debug("Budget released: poolId={}, points={}", poolId, points);
    }

    @Override
    public void consumeBudget(Long poolId, long points) {
        int rows = budgetPoolMapper.consumeBudget(poolId, points);
        if (rows == 0) {
            throw new BusinessException("预算池额度不足");
        }
        log.debug("Budget consumed: poolId={}, points={}", poolId, points);
    }

    @Override
    public void restoreBudget(Long poolId, long points) {
        int rows = budgetPoolMapper.restoreBudget(poolId, points);
        if (rows == 0) {
            log.warn("Budget restore returned 0 rows: poolId={}, points={}", poolId, points);
        }
        log.debug("Budget restored: poolId={}, points={}", poolId, points);
    }

    @Override
    public boolean isPoolValidFor(BudgetPool pool, Long memberLevelId) {
        // Check status
        if (pool.getStatus() == null || pool.getStatus() != BudgetPoolStatus.ACTIVE.getCode()) {
            return false;
        }

        // Check time range
        LocalDateTime now = LocalDateTime.now();
        if (pool.getStartTime() != null && now.isBefore(pool.getStartTime())) {
            return false;
        }
        if (pool.getEndTime() != null && now.isAfter(pool.getEndTime())) {
            return false;
        }

        // Check applicable levels (null means all levels)
        if (pool.getApplicableLevels() != null && !pool.getApplicableLevels().isEmpty()) {
            try {
                List<Integer> levels = objectMapper.readValue(pool.getApplicableLevels(),
                        new TypeReference<List<Integer>>() {});
                if (memberLevelId == null || !levels.contains(memberLevelId.intValue())) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("Failed to parse applicable_levels for pool {}: {}", pool.getId(), e.getMessage());
                return false;
            }
        }

        return true;
    }
}
