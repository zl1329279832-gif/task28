package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("points_flow")
public class PointsFlow {

    @TableId
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("event_id")
    private String eventId;

    @TableField("event_type")
    private String eventType;

    @TableField("points_change")
    private Long pointsChange;

    @TableField("before_points")
    private Long beforePoints;

    @TableField("after_points")
    private Long afterPoints;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("rule_version")
    private Integer ruleVersion;

    @TableField("biz_order_no")
    private String bizOrderNo;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("remark")
    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;
}
