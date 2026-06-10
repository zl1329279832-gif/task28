package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("circuit_breaker")
public class CircuitBreaker {

    @TableId
    private Long id;

    @TableField("pool_id")
    private Long poolId;

    @TableField("status")
    private String status;

    @TableField("failure_count")
    private Integer failureCount;

    @TableField("last_failure_time")
    private LocalDateTime lastFailureTime;

    @TableField("last_state_change")
    private LocalDateTime lastStateChange;

    @TableField("cooldown_minutes")
    private Integer cooldownMinutes;

    @TableField("half_open_count")
    private Integer halfOpenCount;

    @TableField("max_test_requests")
    private Integer maxTestRequests;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
