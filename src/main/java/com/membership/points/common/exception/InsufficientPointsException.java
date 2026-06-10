package com.membership.points.common.exception;

/**
 * 积分不足异常
 */
public class InsufficientPointsException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public InsufficientPointsException(String message) {
        super(message);
    }
}
