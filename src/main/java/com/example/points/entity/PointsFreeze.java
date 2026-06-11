package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("points_freeze")
public class PointsFreeze {

    @TableId
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("freeze_no")
    private String freezeNo;

    @TableField("points")
    private Long points;

    @TableField("biz_order_no")
    private String bizOrderNo;

    @TableField("reason")
    private String reason;

    @TableField("status")
    private Integer status;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("budget_pool_id")
    private Long budgetPoolId;

    @TableField("budget_amount")
    private Long budgetAmount;
}
