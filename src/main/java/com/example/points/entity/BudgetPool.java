package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("budget_pool")
public class BudgetPool {

    @TableId
    private Long id;

    @TableField("activity_code")
    private String activityCode;

    @TableField("activity_name")
    private String activityName;

    @TableField("total_budget")
    private Long totalBudget;

    @TableField("used_budget")
    private Long usedBudget;

    @TableField("frozen_budget")
    private Long frozenBudget;

    @TableField("applicable_levels")
    private String applicableLevels;

    @TableField("daily_limit")
    private Long dailyLimit;

    @TableField("monthly_limit")
    private Long monthlyLimit;

    @TableField("risk_threshold")
    private Long riskThreshold;

    @TableField("circuit_break_rate")
    private Integer circuitBreakRate;

    @TableField("status")
    private Integer status;

    @TableField("effective_start")
    private LocalDateTime effectiveStart;

    @TableField("effective_end")
    private LocalDateTime effectiveEnd;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
