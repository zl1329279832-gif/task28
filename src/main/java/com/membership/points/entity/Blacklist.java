package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("blacklist")
public class Blacklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("block_type")
    private String blockType;

    @TableField("reason")
    private String reason;

    @TableField("operator")
    private String operator;

    @TableField("blocked_at")
    private LocalDateTime blockedAt;

    @TableField("unblocked_at")
    private LocalDateTime unblockedAt;

    @TableField("active")
    private Integer active;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
