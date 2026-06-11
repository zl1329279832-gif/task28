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

    @TableField("pool_name")
    private String poolName;

    @TableField("total_budget")
    private Long totalBudget;

    @TableField("used_budget")
    private Long usedBudget;

    @TableField("frozen_budget")
    private Long frozenBudget;

    @TableField("daily_cap")
    private Long dailyCap;

    @TableField("monthly_cap")
    private Long monthlyCap;

    @TableField("daily_used")
    private Long dailyUsed;

    @TableField("monthly_used")
    private Long monthlyUsed;

    @TableField("daily_reset_date")
    private java.time.LocalDate dailyResetDate;

    @TableField("monthly_reset_date")
    private java.time.LocalDate monthlyResetDate;

    @TableField("applicable_levels")
    private String applicableLevels;

    @TableField("status")
    private Integer status;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
