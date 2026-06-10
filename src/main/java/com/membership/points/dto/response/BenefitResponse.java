package com.membership.points.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BenefitResponse {

    private Long id;

    private String benefitCode;

    private String benefitName;

    private String description;

    private String category;

    private Integer pointsCost;

    private String minLevel;

    private String imageUrl;

    private Integer availableStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
