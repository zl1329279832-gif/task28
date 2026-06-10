package com.example.points.service;

import com.example.points.entity.BudgetPool;

import java.util.List;

public interface BudgetPoolService {

    BudgetPool createPool(BudgetPool pool);

    BudgetPool updatePool(Long poolId, BudgetPool update);

    BudgetPool getPool(Long poolId);

    List<BudgetPool> listActivePools();

    void suspendPool(Long poolId, String operator);

    void reactivatePool(Long poolId, String operator);

    void reserveBudget(Long poolId, long points);

    void releaseBudget(Long poolId, long points);

    void consumeBudget(Long poolId, long points);

    void restoreBudget(Long poolId, long points);

    boolean isPoolValidFor(BudgetPool pool, Long memberLevelId);
}
