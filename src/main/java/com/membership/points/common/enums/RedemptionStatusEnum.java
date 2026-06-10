package com.membership.points.common.enums;

import lombok.Getter;

/**
 * 兑换状态枚举
 */
@Getter
public enum RedemptionStatusEnum {

    PENDING("PENDING", "待处理"),
    FROZEN("FROZEN", "已冻结"),
    COMPLETED("COMPLETED", "已完成"),
    FAILED("FAILED", "已失败"),
    REFUNDED("REFUNDED", "已退款");

    private final String code;
    private final String desc;

    RedemptionStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     *
     * @param code 兑换状态编码
     * @return 兑换状态枚举
     */
    public static RedemptionStatusEnum getByCode(String code) {
        for (RedemptionStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown redemption status code: " + code);
    }
}
