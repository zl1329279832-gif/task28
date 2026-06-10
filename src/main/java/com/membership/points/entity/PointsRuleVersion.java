package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("points_rule_version")
public class PointsRuleVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("version_number")
    private Integer versionNumber;

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

    @TableField("snapshot_json")
    private String snapshotJson;

    @TableField("effective_from")
    private LocalDateTime effectiveFrom;

    @TableField("effective_to")
    private LocalDateTime effectiveTo;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
