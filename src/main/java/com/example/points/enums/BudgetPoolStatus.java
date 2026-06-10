package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BudgetPoolStatus {
    DISABLED(0),
    ENABLED(1),
    CIRCUIT_BROKEN(2);

    private final int code;
}
