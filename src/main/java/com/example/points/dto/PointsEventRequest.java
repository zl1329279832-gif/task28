package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class PointsEventRequest {

    @NotBlank(message = "事件ID不能为空")
    private String eventId;

    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    private Long amount;

    private String bizOrderNo;

    private String activityCode;

    private String remark;
}
