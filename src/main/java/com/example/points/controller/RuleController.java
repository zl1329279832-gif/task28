package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.dto.RuleUpdateRequest;
import com.example.points.entity.PointsRule;
import com.example.points.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/rule")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @GetMapping("/list")
    public ApiResponse<List<PointsRule>> listRules() {
        List<PointsRule> list = ruleService.listRules();
        return ApiResponse.ok(list);
    }

    @PostMapping("/create")
    public ApiResponse<PointsRule> createRule(@RequestBody PointsRule rule) {
        PointsRule created = ruleService.createRule(rule);
        return ApiResponse.ok(created);
    }

    @PutMapping("/update")
    public ApiResponse<PointsRule> updateRule(@Valid @RequestBody RuleUpdateRequest request) {
        PointsRule updated = ruleService.updateRule(request);
        return ApiResponse.ok(updated);
    }
}
