package com.membership.points.common.exception;

/**
 * 黑名单限制异常
 */
public class BlacklistedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public BlacklistedException(String message) {
        super(message);
    }
}
