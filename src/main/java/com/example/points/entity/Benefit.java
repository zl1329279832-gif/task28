package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("benefit")
public class Benefit {

    @TableId
    private Long id;

    @TableField("benefit_name")
    private String benefitName;

    @TableField("benefit_type")
    private String benefitType;

    @TableField("points_cost")
    private Long pointsCost;

    @TableField("total_stock")
    private Integer totalStock;

    @TableField("available_stock")
    private Integer availableStock;

    @TableField("min_level_id")
    private Long minLevelId;

    @TableField("daily_limit")
    private Integer dailyLimit;

    @TableField("total_limit")
    private Integer totalLimit;

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
