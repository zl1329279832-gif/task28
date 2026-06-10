package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.dto.BudgetPoolCreateRequest;
import com.example.points.dto.BudgetPoolUpdateRequest;
import com.example.points.entity.BudgetPool;
import com.example.points.service.BudgetPoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/budget-pool")
@RequiredArgsConstructor
public class BudgetPoolController {

    private final BudgetPoolService budgetPoolService;

    @PostMapping("/create")
    public ApiResponse<BudgetPool> create(@Valid @RequestBody BudgetPoolCreateRequest request) {
        BudgetPool pool = budgetPoolService.createPool(request);
        return ApiResponse.ok(pool);
    }

    @PutMapping("/update")
    public ApiResponse<BudgetPool> update(@Valid @RequestBody BudgetPoolUpdateRequest request) {
        BudgetPool pool = budgetPoolService.updatePool(request);
        return ApiResponse.ok(pool);
    }

    @PostMapping("/{poolId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long poolId,
                                          @RequestParam Integer status,
                                          @RequestParam String operator) {
        budgetPoolService.updatePoolStatus(poolId, status, operator);
        return ApiResponse.ok();
    }

    @PostMapping("/{poolId}/recover")
    public ApiResponse<Void> recover(@PathVariable Long poolId,
                                     @RequestParam String operator) {
        budgetPoolService.recoverPool(poolId, operator);
        return ApiResponse.ok();
    }

    @GetMapping("/list")
    public ApiResponse<List<BudgetPool>> list(@RequestParam(required = false) Integer status) {
        List<BudgetPool> pools = budgetPoolService.listPools(status);
        return ApiResponse.ok(pools);
    }

    @GetMapping("/{activityCode}")
    public ApiResponse<BudgetPool> getByActivityCode(@PathVariable String activityCode) {
        BudgetPool pool = budgetPoolService.getActivePool(activityCode);
        return ApiResponse.ok(pool);
    }
}
