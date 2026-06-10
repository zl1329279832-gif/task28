package com.membership.points.common.exception;

/**
 * 库存不足异常
 */
public class InsufficientStockException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public InsufficientStockException(String message) {
        super(message);
    }
}
