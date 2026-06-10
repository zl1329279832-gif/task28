package com.membership.points.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PointsTransactionResponse {

    private String transactionNo;

    private String transactionType;

    private String source;

    private Long pointsAmount;

    private Long balanceBefore;

    private Long balanceAfter;

    private String referenceId;

    private String remark;

    private String operator;

    private LocalDateTime createdAt;
}
