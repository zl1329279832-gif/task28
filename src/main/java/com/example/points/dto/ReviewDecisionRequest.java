package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ReviewDecisionRequest {

    @NotBlank(message = "复核人不能为空")
    private String reviewer;

    private String remark;
}
