package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_account")
public class PointsAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("available_points")
    private Long availablePoints;

    @TableField("frozen_points")
    private Long frozenPoints;

    @TableField("total_earned")
    private Long totalEarned;

    @TableField("total_spent")
    private Long totalSpent;

    @TableField("total_expired")
    private Long totalExpired;

    @Version
    @TableField("version")
    private Integer version;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
