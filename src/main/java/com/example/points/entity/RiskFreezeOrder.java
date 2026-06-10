package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("risk_freeze_order")
public class RiskFreezeOrder {

    @TableId
    private Long id;

    @TableField("freeze_order_no")
    private String freezeOrderNo;

    @TableField("pool_id")
    private Long poolId;

    @TableField("member_id")
    private Long memberId;

    @TableField("points_freeze_id")
    private Long pointsFreezeId;

    @TableField("risk_event_id")
    private Long riskEventId;

    @TableField("freeze_type")
    private String freezeType;

    @TableField("points")
    private Long points;

    @TableField("review_status")
    private Integer reviewStatus;

    @TableField("reviewer")
    private String reviewer;

    @TableField("review_remark")
    private String reviewRemark;

    @TableField("review_time")
    private LocalDateTime reviewTime;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
