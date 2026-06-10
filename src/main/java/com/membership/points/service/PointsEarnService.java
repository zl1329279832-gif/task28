package com.membership.points.service;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.EarnPointsRequest;

/**
 * 积分获取服务接口
 */
public interface PointsEarnService {

    /**
     * 积分获取
     *
     * @param request 积分获取请求
     * @return 操作结果
     */
    Result<?> earnPoints(EarnPointsRequest request);
}
