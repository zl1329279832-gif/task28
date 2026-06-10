package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class RefundRequest {

    @NotBlank(message = "业务单号不能为空")
    private String bizOrderNo;

    @NotBlank(message = "事件ID不能为空")
    private String eventId;

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    private Long refundAmount;

    private String operator;
}
