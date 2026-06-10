package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("points_account")
public class PointsAccount {

    @TableId
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("available_points")
    private Long availablePoints;

    @TableField("frozen_points")
    private Long frozenPoints;

    @TableField("total_earned")
    private Long totalEarned;

    @TableField("total_consumed")
    private Long totalConsumed;

    @TableField("total_expired")
    private Long totalExpired;

    @TableField("level_id")
    private Long levelId;

    @TableField("monthly_earned")
    private Long monthlyEarned;

    @TableField("monthly_reset_date")
    private LocalDate monthlyResetDate;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
