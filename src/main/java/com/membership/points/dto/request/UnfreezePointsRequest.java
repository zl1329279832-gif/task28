package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class UnfreezePointsRequest {

    @NotBlank
    private String freezeNo;

    @NotBlank
    private String action;  // CONFIRM or CANCEL

    private String remark;
}
