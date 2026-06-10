package com.membership.points.config;

import com.membership.points.common.exception.*;
import com.membership.points.common.result.Result;
import com.membership.points.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientPointsException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleInsufficientPoints(InsufficientPointsException e) {
        log.warn("积分不足: {}", e.getMessage());
        return Result.fail(ResultCode.INSUFFICIENT_POINTS, e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleInsufficientStock(InsufficientStockException e) {
        log.warn("库存不足: {}", e.getMessage());
        return Result.fail(ResultCode.INSUFFICIENT_STOCK, e.getMessage());
    }

    @ExceptionHandler(DuplicateRequestException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> handleDuplicateRequest(DuplicateRequestException e) {
        log.warn("重复请求: {}", e.getMessage());
        return Result.fail(ResultCode.DUPLICATE_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BlacklistedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handleBlacklisted(BlacklistedException e) {
        log.warn("黑名单限制: {}", e.getMessage());
        return Result.fail(ResultCode.BLACKLISTED, e.getMessage());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> handleConcurrencyConflict(ConcurrencyConflictException e) {
        log.warn("并发冲突: {}", e.getMessage());
        return Result.fail(ResultCode.CONCURRENCY_CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(ResultCode.BUSINESS_ERROR, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBind(BindException e) {
        String msg = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR, "系统内部错误");
    }
}
