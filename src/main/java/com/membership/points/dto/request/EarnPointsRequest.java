package com.membership.points.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class EarnPointsRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    @NotBlank(message = "积分来源不能为空")
    private String source;  // REGISTER/CHECKIN/PURCHASE/ACTIVITY

    private String referenceId;  // 外部订单号(消费返积分时必填)

    private BigDecimal orderAmount;  // 订单金额(消费返积分时必填)

    @NotBlank(message = "幂等键不能为空")
    private String idempotentKey;

    private String remark;
}
