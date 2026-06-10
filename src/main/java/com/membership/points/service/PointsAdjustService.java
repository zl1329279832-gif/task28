package com.membership.points.service;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.AdjustPointsRequest;

/**
 * 积分调整服务接口
 */
public interface PointsAdjustService {

    /**
     * 积分调整（调增或调减）
     *
     * @param request 积分调整请求
     * @return 操作结果
     */
    Result<?> adjustPoints(AdjustPointsRequest request);
}
