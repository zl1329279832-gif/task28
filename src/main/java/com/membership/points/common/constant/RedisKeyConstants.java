package com.membership.points.common.constant;

/**
 * Redis缓存Key常量
 */
public class RedisKeyConstants {

    private RedisKeyConstants() {
        throw new IllegalStateException("Constants class");
    }

    /**
     * 积分账户缓存前缀
     */
    public static final String POINTS_ACCOUNT = "points:account:";

    /**
     * 会员信息缓存前缀
     */
    public static final String MEMBER_INFO = "member:info:";

    /**
     * 会员等级缓存前缀
     */
    public static final String MEMBER_LEVEL = "member:level:";

    /**
     * 权益详情缓存前缀
     */
    public static final String BENEFIT_DETAIL = "benefit:detail:";

    /**
     * 权益库存缓存前缀
     */
    public static final String BENEFIT_INVENTORY = "benefit:inventory:";

    /**
     * 会员黑名单缓存前缀
     */
    public static final String BLACKLIST_MEMBER = "blacklist:member:";

    /**
     * 兑换分布式锁前缀
     */
    public static final String LOCK_REDEEM = "lock:redeem:";

    /**
     * 获取积分分布式锁前缀
     */
    public static final String LOCK_EARN = "lock:earn:";

    /**
     * 冻结分布式锁前缀
     */
    public static final String LOCK_FREEZE = "lock:freeze:";
}
