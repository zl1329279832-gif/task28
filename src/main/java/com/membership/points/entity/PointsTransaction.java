package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_transaction")
public class PointsTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("transaction_no")
    private String transactionNo;

    @TableField("member_id")
    private Long memberId;

    @TableField("transaction_type")
    private String transactionType;

    @TableField("source")
    private String source;

    @TableField("points_amount")
    private Long pointsAmount;

    @TableField("balance_before")
    private Long balanceBefore;

    @TableField("balance_after")
    private Long balanceAfter;

    @TableField("frozen_before")
    private Long frozenBefore;

    @TableField("frozen_after")
    private Long frozenAfter;

    @TableField("rule_version_id")
    private Long ruleVersionId;

    @TableField("batch_id")
    private Long batchId;

    @TableField("reference_id")
    private String referenceId;

    @TableField("remark")
    private String remark;

    @TableField("operator")
    private String operator;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
