package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class FreezeRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    @NotNull(message = "积分不能为空")
    private Long points;

    @NotBlank(message = "冻结单号不能为空")
    private String freezeNo;

    @NotBlank(message = "业务单号不能为空")
    private String bizOrderNo;

    private String reason;

    private Integer freezeHours;
}
