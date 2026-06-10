package com.membership.points.common.result;

import lombok.Getter;

/**
 * 统一响应状态码枚举
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BUSINESS_ERROR(400, "业务异常"),
    PARAM_ERROR(400, "参数错误"),
    INSUFFICIENT_POINTS(4001, "积分不足"),
    INSUFFICIENT_STOCK(4002, "库存不足"),
    DUPLICATE_REQUEST(4003, "重复请求"),
    BLACKLISTED(4004, "黑名单限制"),
    CONCURRENCY_CONFLICT(4005, "并发冲突，请重试"),
    NOT_FOUND(404, "资源不存在"),
    SYSTEM_ERROR(500, "系统内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
