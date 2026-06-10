package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_freeze")
public class PointsFreeze {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("freeze_no")
    private String freezeNo;

    @TableField("member_id")
    private Long memberId;

    @TableField("frozen_points")
    private Long frozenPoints;

    @TableField("status")
    private String status;

    @TableField("redemption_order_id")
    private Long redemptionOrderId;

    @TableField("freeze_detail")
    private String freezeDetail;

    @TableField("frozen_at")
    private LocalDateTime frozenAt;

    @TableField("unfrozen_at")
    private LocalDateTime unfrozenAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
