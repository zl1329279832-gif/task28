package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_batch")
public class PointsBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("source")
    private String source;

    @TableField("original_points")
    private Long originalPoints;

    @TableField("remaining_points")
    private Long remainingPoints;

    @TableField("frozen_points")
    private Long frozenPoints;

    @TableField("rule_version_id")
    private Long ruleVersionId;

    @TableField("earn_transaction_id")
    private Long earnTransactionId;

    @TableField("earned_at")
    private LocalDateTime earnedAt;

    @TableField("expire_at")
    private LocalDateTime expireAt;

    @TableField("expired")
    private Integer expired;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
