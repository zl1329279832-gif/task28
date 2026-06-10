package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReviewResult {
    APPROVED(1),
    REJECTED(2);

    private final int code;
}
