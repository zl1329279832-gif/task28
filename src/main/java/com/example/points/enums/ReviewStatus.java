package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReviewStatus {

    PENDING(0),
    APPROVED(1),
    REJECTED(2);

    private final int code;
}
