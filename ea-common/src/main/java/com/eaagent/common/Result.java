package com.eaagent.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应包装（详细设计 7.1：所有写操作带 request_id 头实现幂等）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private String requestId;
    private T data;

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), null, data);
    }

    public static <T> Result<T> ok(String requestId, T data) {
        return new Result<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), requestId, data);
    }

    public static <T> Result<T> error(ErrorCode code) {
        return new Result<>(code.getCode(), code.getMessage(), null, null);
    }

    public static <T> Result<T> error(ErrorCode code, String message) {
        return new Result<>(code.getCode(), message, null, null);
    }

    public static <T> Result<T> error(ErrorCode code, String message, String requestId, T data) {
        return new Result<>(code.getCode(), message, requestId, data);
    }

    public boolean isSuccess() {
        return code == ErrorCode.OK.getCode();
    }
}