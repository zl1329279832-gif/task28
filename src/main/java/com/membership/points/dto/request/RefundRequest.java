package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RefundRequest {

    @NotBlank
    private String reason;

    @NotBlank
    private String operator;
}
