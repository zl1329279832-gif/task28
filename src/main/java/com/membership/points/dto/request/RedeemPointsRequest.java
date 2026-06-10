package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class RedeemPointsRequest {

    @NotNull
    private Long memberId;

    @NotNull
    private Long benefitId;

    @NotBlank
    private String skuCode;

    @Min(1)
    private Integer quantity = 1;

    @NotBlank
    private String idempotentKey;
}
