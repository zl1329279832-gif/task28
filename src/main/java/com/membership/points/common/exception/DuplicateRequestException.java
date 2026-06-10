package com.membership.points.common.exception;

/**
 * 重复请求异常
 */
public class DuplicateRequestException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public DuplicateRequestException(String message) {
        super(message);
    }
}
