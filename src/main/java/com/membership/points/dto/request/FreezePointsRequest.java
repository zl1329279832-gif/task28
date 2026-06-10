package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class FreezePointsRequest {

    @NotNull
    private Long memberId;

    @NotNull
    @Min(1)
    private Long points;

    private Long redemptionOrderId;

    private String remark;
}
