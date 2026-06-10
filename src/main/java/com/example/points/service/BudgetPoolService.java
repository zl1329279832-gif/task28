package com.example.points.service;

import com.example.points.dto.BudgetPoolCreateRequest;
import com.example.points.dto.BudgetPoolUpdateRequest;
import com.example.points.entity.BudgetPool;

import java.util.List;

public interface BudgetPoolService {

    BudgetPool createPool(BudgetPoolCreateRequest request);

    BudgetPool updatePool(BudgetPoolUpdateRequest request);

    void updatePoolStatus(Long poolId, Integer status, String operator);

    BudgetPool getActivePool(String activityCode);

    void occupyBudget(Long poolId, Long memberId, Long points,
                      String eventId, String bizOrderNo);

    void releaseBudget(Long poolId, Long memberId, Long points,
                       String eventId, String bizOrderNo);

    boolean checkCircuitBreak(Long poolId);

    void triggerCircuitBreak(Long poolId, String reason);

    void recoverPool(Long poolId, String operator);

    List<BudgetPool> listPools(Integer status);
}
