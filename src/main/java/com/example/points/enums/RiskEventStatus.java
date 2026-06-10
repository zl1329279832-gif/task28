package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RiskEventStatus {

    OPEN(1),
    RESOLVED(2),
    IGNORED(3);

    private final int code;
}
