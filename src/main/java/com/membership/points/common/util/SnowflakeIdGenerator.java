package com.membership.points.common.util;

import cn.hutool.core.util.IdUtil;

/**
 * 雪花算法ID生成器
 * 基于Hutool的IdUtil实现
 */
public class SnowflakeIdGenerator {

    private SnowflakeIdGenerator() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 生成雪花算法ID（字符串形式）
     *
     * @return 雪花算法ID字符串
     */
    public static String nextId() {
        return IdUtil.getSnowflakeNextIdStr();
    }

    /**
     * 生成带前缀的交易流水号
     *
     * @param prefix 前缀
     * @return 前缀 + 雪花算法ID
     */
    public static String generateTransactionNo(String prefix) {
        return prefix + IdUtil.getSnowflakeNextIdStr();
    }

    /**
     * 生成兑换订单号
     *
     * @return "RD" + 雪花算法ID
     */
    public static String generateOrderNo() {
        return "RD" + IdUtil.getSnowflakeNextIdStr();
    }
}
