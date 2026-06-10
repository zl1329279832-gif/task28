package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("review_order")
public class ReviewOrder {

    @TableId
    private Long id;

    @TableField("review_no")
    private String reviewNo;

    @TableField("risk_event_id")
    private Long riskEventId;

    @TableField("member_id")
    private Long memberId;

    @TableField("review_result")
    private Integer reviewResult;

    @TableField("reviewer")
    private String reviewer;

    @TableField("review_comment")
    private String reviewComment;

    @TableField("action_taken")
    private String actionTaken;

    @TableField("review_time")
    private LocalDateTime reviewTime;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
