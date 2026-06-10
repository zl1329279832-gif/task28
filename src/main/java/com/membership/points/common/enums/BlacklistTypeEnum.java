package com.membership.points.common.enums;

import lombok.Getter;

/**
 * 黑名单类型枚举
 */
@Getter
public enum BlacklistTypeEnum {

    EARN_BLOCK("EARN_BLOCK", "获取限制"),
    REDEEM_BLOCK("REDEEM_BLOCK", "兑换限制"),
    FULL_BLOCK("FULL_BLOCK", "全部限制");

    private final String code;
    private final String desc;

    BlacklistTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     *
     * @param code 黑名单类型编码
     * @return 黑名单类型枚举
     */
    public static BlacklistTypeEnum getByCode(String code) {
        for (BlacklistTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown blacklist type code: " + code);
    }
}
