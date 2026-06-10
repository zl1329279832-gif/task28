package com.membership.points.common.enums;

import lombok.Getter;

/**
 * 冻结状态枚举
 */
@Getter
public enum FreezeStatusEnum {

    FROZEN("FROZEN", "已冻结"),
    UNFROZEN_SUCCESS("UNFROZEN_SUCCESS", "解冻成功"),
    UNFROZEN_FAIL("UNFROZEN_FAIL", "解冻失败");

    private final String code;
    private final String desc;

    FreezeStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     *
     * @param code 冻结状态编码
     * @return 冻结状态枚举
     */
    public static FreezeStatusEnum getByCode(String code) {
        for (FreezeStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown freeze status code: " + code);
    }
}
