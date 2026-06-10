package com.membership.points.service;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.RedeemPointsRequest;
import com.membership.points.dto.response.RedemptionOrderResponse;

/**
 * 兑换服务接口
 */
public interface RedemptionService {

    /**
     * 积分兑换权益
     *
     * @param request 兑换请求
     * @return 兑换订单响应
     */
    Result<RedemptionOrderResponse> redeem(RedeemPointsRequest request);
}
