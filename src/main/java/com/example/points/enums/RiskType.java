package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RiskType {
    HIGH_FREQUENCY("HIGH_FREQUENCY", "短时间高频领取"),
    ABNORMAL_REFUND("ABNORMAL_REFUND", "异常退款"),
    BLACKLIST_HIT("BLACKLIST_HIT", "黑名单命中"),
    BUDGET_EXHAUSTED("BUDGET_EXHAUSTED", "预算耗尽");

    private final String code;
    private final String desc;
}
