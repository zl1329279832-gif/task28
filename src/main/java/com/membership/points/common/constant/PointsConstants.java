package com.membership.points.common.constant;

/**
 * 积分系统常量
 */
public class PointsConstants {

    private PointsConstants() {
        throw new IllegalStateException("Constants class");
    }

    /**
     * 默认积分过期月数
     */
    public static final int DEFAULT_EXPIRE_MONTHS = 12;

    /**
     * 乐观锁最大重试次数
     */
    public static final int MAX_RETRY_TIMES = 3;

    /**
     * 过期任务默认批处理大小
     */
    public static final int DEFAULT_BATCH_SIZE = 500;

    /**
     * 退款最大天数
     */
    public static final int DEFAULT_REFUND_MAX_DAYS = 30;
}
