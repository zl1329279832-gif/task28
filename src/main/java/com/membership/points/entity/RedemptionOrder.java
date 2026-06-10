package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("redemption_order")
public class RedemptionOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("member_id")
    private Long memberId;

    @TableField("benefit_id")
    private Long benefitId;

    @TableField("inventory_id")
    private Long inventoryId;

    @TableField("quantity")
    private Integer quantity;

    @TableField("points_cost")
    private Integer pointsCost;

    @TableField("status")
    private String status;

    @TableField("freeze_id")
    private Long freezeId;

    @TableField("refund_reason")
    private String refundReason;

    @TableField("refunded_at")
    private LocalDateTime refundedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
