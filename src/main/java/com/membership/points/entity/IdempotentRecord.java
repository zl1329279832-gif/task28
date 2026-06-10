package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("idempotent_record")
public class IdempotentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("idempotent_key")
    private String idempotentKey;

    @TableField("business_type")
    private String businessType;

    @TableField("result_status")
    private String resultStatus;

    @TableField("result_data")
    private String resultData;

    @TableField("expire_at")
    private LocalDateTime expireAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
