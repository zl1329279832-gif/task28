package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.time.LocalDateTime;

@Data
public class TransactionQueryRequest {

    private Long memberId;

    private String transactionType;

    private String source;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Min(1)
    private Integer pageNum = 1;

    @Min(1)
    @Max(100)
    private Integer pageSize = 20;
}
