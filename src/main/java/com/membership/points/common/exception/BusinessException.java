package com.membership.points.common.exception;

import com.membership.points.common.result.ResultCode;

/**
 * 业务异常基类
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private ResultCode resultCode;

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
