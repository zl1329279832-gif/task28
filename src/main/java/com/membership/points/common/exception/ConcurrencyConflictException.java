package com.membership.points.common.exception;

/**
 * 并发冲突异常
 */
public class ConcurrencyConflictException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ConcurrencyConflictException(String message) {
        super(message);
    }
}
