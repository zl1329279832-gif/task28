package com.membership.points.common.enums;

import lombok.Getter;

/**
 * 积分交易类型枚举
 */
@Getter
public enum PointsTransactionTypeEnum {

    EARN("EARN", "积分获取"),
    SPEND("SPEND", "积分消费"),
    EXPIRE("EXPIRE", "积分过期"),
    ADJUST_ADD("ADJUST_ADD", "积分调增"),
    ADJUST_DEDUCT("ADJUST_DEDUCT", "积分调减"),
    FREEZE("FREEZE", "积分冻结"),
    UNFREEZE("UNFREEZE", "积分解冻");

    private final String code;
    private final String desc;

    PointsTransactionTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     *
     * @param code 交易类型编码
     * @return 交易类型枚举
     */
    public static PointsTransactionTypeEnum getByCode(String code) {
        for (PointsTransactionTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown transaction type code: " + code);
    }
}
