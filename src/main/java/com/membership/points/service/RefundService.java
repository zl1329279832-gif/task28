package com.membership.points.service;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.RefundRequest;

/**
 * 退款服务接口
 */
public interface RefundService {

    /**
     * 退款兑换订单
     *
     * @param orderId 订单ID
     * @param request 退款请求
     * @return 退款结果
     */
    Result<?> refundOrder(Long orderId, RefundRequest request);
}
