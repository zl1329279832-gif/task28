package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("points_rule")
public class PointsRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_code")
    private String ruleCode;

    @TableField("rule_name")
    private String ruleName;

    @TableField("source")
    private String source;

    @TableField("base_points")
    private Integer basePoints;

    @TableField("multiplier")
    private BigDecimal multiplier;

    @TableField("points_per_yuan")
    private Integer pointsPerYuan;

    @TableField("monthly_cap")
    private Integer monthlyCap;

    @TableField("expire_months")
    private Integer expireMonths;

    @TableField("enabled")
    private Integer enabled;

    @TableField("current_version")
    private Integer currentVersion;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
