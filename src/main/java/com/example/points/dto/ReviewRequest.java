package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class ReviewRequest {

    @NotBlank(message = "复核单号不能为空")
    private String reviewNo;

    @NotNull(message = "复核结果不能为空")
    private Integer reviewResult;

    @NotBlank(message = "复核人不能为空")
    private String reviewer;

    private String reviewComment;

    private Boolean addBlacklist;
}
