package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RiskEventStatus {
    PENDING(0),
    REVIEWING(1),
    APPROVED(2),
    REJECTED(3);

    private final int code;
}
