package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("budget_usage_log")
public class BudgetUsageLog {

    @TableId
    private Long id;

    @TableField("pool_id")
    private Long poolId;

    @TableField("member_id")
    private Long memberId;

    @TableField("event_id")
    private String eventId;

    @TableField("usage_type")
    private String usageType;

    @TableField("points")
    private Long points;

    @TableField("biz_order_no")
    private String bizOrderNo;

    @TableField("remark")
    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;
}
