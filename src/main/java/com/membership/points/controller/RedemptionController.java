package com.membership.points.controller;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.RedeemPointsRequest;
import com.membership.points.dto.request.RefundRequest;
import com.membership.points.dto.response.RedemptionOrderResponse;
import com.membership.points.service.RedemptionService;
import com.membership.points.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 积分兑换控制器
 */
@RestController
@RequestMapping("/api/redemption")
@RequiredArgsConstructor
public class RedemptionController {

    private final RedemptionService redemptionService;
    private final RefundService refundService;

    @PostMapping("/redeem")
    public Result<RedemptionOrderResponse> redeem(@Valid @RequestBody RedeemPointsRequest request) {
        return redemptionService.redeem(request);
    }

    @PostMapping("/refund/{orderId}")
    public Result<?> refund(@PathVariable Long orderId, @Valid @RequestBody RefundRequest request) {
        return refundService.refundOrder(orderId, request);
    }
}
