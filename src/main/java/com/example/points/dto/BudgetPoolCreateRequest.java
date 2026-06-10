package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
public class BudgetPoolCreateRequest {

    @NotBlank(message = "活动代码不能为空")
    private String activityCode;

    @NotBlank(message = "活动名称不能为空")
    private String activityName;

    @NotNull(message = "预算总额不能为空")
    private Long totalBudget;

    private String applicableLevels;

    private Long dailyLimit;

    private Long monthlyLimit;

    private Long riskThreshold;

    private Integer circuitBreakRate;

    private LocalDateTime effectiveStart;

    private LocalDateTime effectiveEnd;

    @NotBlank(message = "操作人不能为空")
    private String operator;
}
