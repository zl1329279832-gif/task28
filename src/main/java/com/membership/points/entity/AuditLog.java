package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("operation")
    private String operation;

    @TableField("target_type")
    private String targetType;

    @TableField("target_id")
    private Long targetId;

    @TableField("member_id")
    private Long memberId;

    @TableField("operator")
    private String operator;

    @TableField("detail")
    private String detail;

    @TableField("ip_address")
    private String ipAddress;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
