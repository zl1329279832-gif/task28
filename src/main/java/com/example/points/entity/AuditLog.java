package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_log")
public class AuditLog {

    @TableId
    private Long id;

    @TableField("module")
    private String module;

    @TableField("action")
    private String action;

    @TableField("target_id")
    private String targetId;

    @TableField("target_type")
    private String targetType;

    @TableField("before_value")
    private String beforeValue;

    @TableField("after_value")
    private String afterValue;

    @TableField("operator")
    private String operator;

    @TableField("ip")
    private String ip;

    @TableField("create_time")
    private LocalDateTime createTime;
}
