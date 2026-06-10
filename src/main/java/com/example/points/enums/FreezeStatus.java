package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FreezeStatus {

    FROZEN(0),
    UNFROZEN(1),
    DEDUCTED(2),
    EXPIRED(3);

    private final int code;
}
