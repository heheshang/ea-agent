package com.eaagent.common;

import lombok.Getter;

/**
 * 业务异常：携带错误码段位（10xxx-16xxx），由全局异常处理器转换为 Result。
 */
@Getter
public class BizException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object data;   // 幂等重放等场景携带首次结果

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    public static BizException of(ErrorCode code) {
        return new BizException(code);
    }
}