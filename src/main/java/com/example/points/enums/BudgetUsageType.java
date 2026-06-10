package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BudgetUsageType {
    OCCUPY("OCCUPY", "占用"),
    RELEASE("RELEASE", "释放"),
    REFUND("REFUND", "退款回补");

    private final String code;
    private final String desc;
}
