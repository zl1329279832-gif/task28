package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
public class BudgetPoolUpdateRequest {

    @NotNull(message = "预算池ID不能为空")
    private Long poolId;

    private String activityName;

    private Long totalBudget;

    private String applicableLevels;

    private Long dailyLimit;

    private Long monthlyLimit;

    private Long riskThreshold;

    private Integer circuitBreakRate;

    private Integer status;

    private LocalDateTime effectiveStart;

    private LocalDateTime effectiveEnd;

    @NotBlank(message = "操作人不能为空")
    private String operator;
}
