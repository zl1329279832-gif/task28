package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
public class CreateBenefitRequest {

    @NotBlank
    private String benefitCode;

    @NotBlank
    private String benefitName;

    private String description;

    private String category;

    @NotNull
    @Min(1)
    private Integer pointsCost;

    private String minLevel;  // minimum member level required, null=any

    private String imageUrl;

    @NotBlank
    private String skuCode;

    private String skuName;

    @NotNull
    @Min(0)
    private Integer totalStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
