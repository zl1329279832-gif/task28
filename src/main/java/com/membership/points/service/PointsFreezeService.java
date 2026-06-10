package com.membership.points.service;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.FreezePointsRequest;
import com.membership.points.dto.request.UnfreezePointsRequest;

/**
 * 积分冻结服务接口
 */
public interface PointsFreezeService {

    /**
     * 冻结积分
     *
     * @param request 冻结积分请求
     * @return 操作结果，包含冻结流水号freeze_no
     */
    Result<String> freezePoints(FreezePointsRequest request);

    /**
     * 解冻积分（确认扣减或取消恢复）
     *
     * @param request 解冻积分请求
     * @return 操作结果
     */
    Result<?> unfreezePoints(UnfreezePointsRequest request);
}
