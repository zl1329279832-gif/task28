package com.example.points.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> ok() {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .build();
    }

    public static <T> ApiResponse<T> fail(String msg) {
        return ApiResponse.<T>builder()
                .code(500)
                .message(msg)
                .build();
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(msg)
                .build();
    }
}
