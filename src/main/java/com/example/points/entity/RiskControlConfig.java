package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("risk_control_config")
public class RiskControlConfig {

    @TableId
    private Long id;

    @TableField("pool_id")
    private Long poolId;

    @TableField("rule_type")
    private String ruleType;

    @TableField("threshold_value")
    private String thresholdValue;

    @TableField("enabled")
    private Integer enabled;

    @TableField("cooldown_minutes")
    private Integer cooldownMinutes;

    @TableField("max_test_requests")
    private Integer maxTestRequests;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
