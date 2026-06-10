package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class RedeemRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    @NotNull(message = "权益ID不能为空")
    private Long benefitId;

    @NotBlank(message = "事件ID不能为空")
    private String eventId;

    private String bizOrderNo;

    private Long budgetPoolId;
}
