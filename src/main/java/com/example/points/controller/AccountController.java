package com.example.points.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.points.common.ApiResponse;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsFlow;
import com.example.points.service.PointsAccountService;
import com.example.points.service.PointsFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final PointsAccountService accountService;
    private final PointsFlowService flowService;

    @GetMapping("/{memberId}")
    public ApiResponse<PointsAccount> getAccount(@PathVariable Long memberId) {
        PointsAccount account = accountService.getOrCreateAccount(memberId);
        return ApiResponse.ok(account);
    }

    @GetMapping("/{memberId}/flows")
    public ApiResponse<Page<PointsFlow>> getFlows(
            @PathVariable Long memberId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String eventType) {
        Page<PointsFlow> result = flowService.queryFlows(memberId, eventType, page, size);
        return ApiResponse.ok(result);
    }
}
