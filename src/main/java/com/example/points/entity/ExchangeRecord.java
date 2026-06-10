package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("exchange_record")
public class ExchangeRecord {

    @TableId
    private Long id;

    @TableField("member_id")
    private Long memberId;

    @TableField("benefit_id")
    private Long benefitId;

    @TableField("exchange_no")
    private String exchangeNo;

    @TableField("points_cost")
    private Long pointsCost;

    @TableField("status")
    private Integer status;

    @TableField("biz_order_no")
    private String bizOrderNo;

    @TableField("refund_time")
    private LocalDateTime refundTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
