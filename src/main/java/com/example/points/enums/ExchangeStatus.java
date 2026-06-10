package com.example.points.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ExchangeStatus {

    EXCHANGING(0),
    COMPLETED(1),
    CANCELLED(2),
    REFUNDED(3);

    private final int code;
}
