package com.membership.points.service;

/**
 * 积分过期服务接口
 */
public interface PointsExpirationService {

    /**
     * 批量处理过期积分批次
     *
     * @return 过期处理的批次数量
     */
    int expireBatch();
}
