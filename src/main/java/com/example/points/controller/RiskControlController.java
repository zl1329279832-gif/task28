package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.entity.RiskEvent;
import com.example.points.service.RiskControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class RiskControlController {

    private final RiskControlService riskControlService;

    @GetMapping("/events")
    public ApiResponse<List<RiskEvent>> listRiskEvents(
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) String riskType,
            @RequestParam(required = false) Integer status) {
        List<RiskEvent> events = riskControlService.listRiskEvents(memberId, riskType, status);
        return ApiResponse.ok(events);
    }
}
