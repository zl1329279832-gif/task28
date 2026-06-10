package com.example.points.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
public class RuleUpdateRequest {

    @NotNull(message = "规则ID不能为空")
    private Long ruleId;

    private String ruleName;

    private String ruleValue;

    private Integer status;

    private LocalDateTime effectiveStart;

    private LocalDateTime effectiveEnd;

    @NotBlank(message = "操作人不能为空")
    private String operator;
}
