package com.membership.points.common.enums;

import lombok.Getter;

/**
 * 积分来源枚举
 */
@Getter
public enum PointsSourceEnum {

    REGISTER("REGISTER", "注册奖励"),
    CHECKIN("CHECKIN", "签到奖励"),
    PURCHASE("PURCHASE", "消费获取"),
    ACTIVITY("ACTIVITY", "活动奖励"),
    ADMIN("ADMIN", "管理员调整"),
    REDEMPTION("REDEMPTION", "积分兑换"),
    EXPIRATION("EXPIRATION", "积分过期"),
    REFUND("REFUND", "退款返还");

    private final String code;
    private final String desc;

    PointsSourceEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     *
     * @param code 来源编码
     * @return 积分来源枚举
     */
    public static PointsSourceEnum getByCode(String code) {
        for (PointsSourceEnum source : values()) {
            if (source.getCode().equals(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown points source code: " + code);
    }
}
