package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.dto.ReviewDecisionRequest;
import com.example.points.entity.CircuitBreaker;
import com.example.points.entity.RiskFreezeOrder;
import com.example.points.service.CircuitBreakerService;
import com.example.points.service.RiskFreezeReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class RiskReviewController {

    private final RiskFreezeReviewService riskFreezeReviewService;
    private final CircuitBreakerService circuitBreakerService;

    @GetMapping("/freeze-orders/pending")
    public ApiResponse<List<RiskFreezeOrder>> listPending() {
        List<RiskFreezeOrder> orders = riskFreezeReviewService.listPendingReviews();
        return ApiResponse.ok(orders);
    }

    @GetMapping("/freeze-orders/{id}")
    public ApiResponse<RiskFreezeOrder> getReview(@PathVariable Long id) {
        RiskFreezeOrder order = riskFreezeReviewService.getReview(id);
        return ApiResponse.ok(order);
    }

    @PostMapping("/freeze-orders/{id}/approve")
    public ApiResponse<Void> approve(@PathVariable Long id,
                                      @Valid @RequestBody ReviewDecisionRequest request) {
        riskFreezeReviewService.approve(id, request);
        return ApiResponse.ok();
    }

    @PostMapping("/freeze-orders/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id,
                                     @Valid @RequestBody ReviewDecisionRequest request) {
        riskFreezeReviewService.reject(id, request);
        return ApiResponse.ok();
    }

    @GetMapping("/circuit-breaker/{poolId}")
    public ApiResponse<CircuitBreaker> getCircuitBreaker(@PathVariable Long poolId) {
        CircuitBreaker cb = circuitBreakerService.getByPoolId(poolId);
        return ApiResponse.ok(cb);
    }

    @PostMapping("/circuit-breaker/{poolId}/close")
    public ApiResponse<Void> closeCircuitBreaker(@PathVariable Long poolId,
                                                   @RequestParam String operator) {
        circuitBreakerService.manualClose(poolId, operator);
        return ApiResponse.ok();
    }

    @PostMapping("/circuit-breaker/{poolId}/open")
    public ApiResponse<Void> openCircuitBreaker(@PathVariable Long poolId,
                                                  @RequestParam String operator) {
        circuitBreakerService.manualOpen(poolId, operator);
        return ApiResponse.ok();
    }
}
