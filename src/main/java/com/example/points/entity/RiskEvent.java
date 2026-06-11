package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("risk_event")
public class RiskEvent {

    @TableId
    private Long id;

    @TableField("pool_id")
    private Long poolId;

    @TableField("member_id")
    private Long memberId;

    @TableField("event_type")
    private String eventType;

    @TableField("flow_id")
    private Long flowId;

    @TableField("detail")
    private String detail;

    @TableField("status")
    private Integer status;

    @TableField("idempotent_key")
    private String idempotentKey;

    @TableField("create_time")
    private LocalDateTime createTime;
}
