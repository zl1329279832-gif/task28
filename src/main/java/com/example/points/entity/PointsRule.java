package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("points_rule")
public class PointsRule {

    @TableId
    private Long id;

    @TableField("rule_code")
    private String ruleCode;

    @TableField("rule_name")
    private String ruleName;

    @TableField("rule_type")
    private String ruleType;

    @TableField("rule_value")
    private String ruleValue;

    @TableField("priority")
    private Integer priority;

    @TableField("version")
    private Integer version;

    @TableField("status")
    private Integer status;

    @TableField("effective_start")
    private LocalDateTime effectiveStart;

    @TableField("effective_end")
    private LocalDateTime effectiveEnd;

    @TableField("description")
    private String description;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
