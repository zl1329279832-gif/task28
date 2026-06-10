package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("blacklist")
public class Blacklist {

    @TableId
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("reason")
    private String reason;

    @TableField("status")
    private Integer status;

    @TableField("operator")
    private String operator;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
