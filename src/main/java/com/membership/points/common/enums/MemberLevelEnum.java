package com.membership.points.common.enums;

import lombok.Getter;

/**
 * 会员等级枚举
 */
@Getter
public enum MemberLevelEnum {

    BRONZE(1, "青铜"),
    SILVER(2, "白银"),
    GOLD(3, "黄金"),
    PLATINUM(4, "铂金");

    private final int code;
    private final String desc;

    MemberLevelEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     *
     * @param code 等级编码
     * @return 会员等级枚举
     */
    public static MemberLevelEnum getByCode(int code) {
        for (MemberLevelEnum level : values()) {
            if (level.getCode() == code) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown member level code: " + code);
    }
}
