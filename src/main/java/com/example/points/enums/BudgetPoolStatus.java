package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BudgetPoolStatus {

    ACTIVE(1),
    EXHAUSTED(2),
    SUSPENDED(3),
    EXPIRED(4);

    private final int code;
}
