package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class AdjustPointsRequest {

    @NotNull
    private Long memberId;

    @NotNull
    private Long points;  // positive=add, negative=deduct

    @NotBlank
    private String reason;

    @NotBlank
    private String operator;  // admin username

    @NotBlank
    private String idempotentKey;
}
