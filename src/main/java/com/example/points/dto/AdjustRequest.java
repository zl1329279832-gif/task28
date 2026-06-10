package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class AdjustRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    @NotNull(message = "积分不能为空")
    private Long points;

    @NotBlank(message = "事件ID不能为空")
    private String eventId;

    @NotBlank(message = "操作人不能为空")
    private String operator;

    private String reason;
}
