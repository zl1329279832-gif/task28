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

    @TableField("event_no")
    private String eventNo;

    @TableField("member_id")
    private Long memberId;

    @TableField("risk_type")
    private String riskType;

    @TableField("risk_detail")
    private String riskDetail;

    @TableField("related_flow_ids")
    private String relatedFlowIds;

    @TableField("related_freeze_no")
    private String relatedFreezeNo;

    @TableField("pool_id")
    private Long poolId;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
