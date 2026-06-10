package com.membership.points.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedemptionOrderResponse {

    private String orderNo;

    private Long memberId;

    private String benefitName;

    private String skuCode;

    private Integer quantity;

    private Integer pointsCost;

    private String status;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
}
