package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.entity.BudgetPool;
import com.example.points.service.BudgetPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/budget-pool")
@RequiredArgsConstructor
public class BudgetPoolController {

    private final BudgetPoolService budgetPoolService;

    @PostMapping("/create")
    public ApiResponse<BudgetPool> create(@RequestBody BudgetPool pool) {
        BudgetPool created = budgetPoolService.createPool(pool);
        return ApiResponse.ok(created);
    }

    @PutMapping("/{poolId}")
    public ApiResponse<BudgetPool> update(@PathVariable Long poolId, @RequestBody BudgetPool pool) {
        BudgetPool updated = budgetPoolService.updatePool(poolId, pool);
        return ApiResponse.ok(updated);
    }

    @GetMapping("/{poolId}")
    public ApiResponse<BudgetPool> get(@PathVariable Long poolId) {
        BudgetPool pool = budgetPoolService.getPool(poolId);
        return ApiResponse.ok(pool);
    }

    @GetMapping("/list")
    public ApiResponse<List<BudgetPool>> listActive() {
        List<BudgetPool> pools = budgetPoolService.listActivePools();
        return ApiResponse.ok(pools);
    }

    @PostMapping("/{poolId}/suspend")
    public ApiResponse<Void> suspend(@PathVariable Long poolId,
                                     @RequestParam String operator) {
        budgetPoolService.suspendPool(poolId, operator);
        return ApiResponse.ok();
    }

    @PostMapping("/{poolId}/reactivate")
    public ApiResponse<Void> reactivate(@PathVariable Long poolId,
                                        @RequestParam String operator) {
        budgetPoolService.reactivatePool(poolId, operator);
        return ApiResponse.ok();
    }
}
