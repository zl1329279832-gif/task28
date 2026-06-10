package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class BlacklistRequest {

    @NotNull
    private Long memberId;

    @NotBlank
    private String blockType;  // EARN_BLOCK/REDEEM_BLOCK/FULL_BLOCK

    @NotBlank
    private String reason;

    @NotBlank
    private String operator;
}
