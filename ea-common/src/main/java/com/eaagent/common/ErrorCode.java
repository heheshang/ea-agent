package com.eaagent.common;

import lombok.Getter;

/**
 * 业务错误码（规范源：详细设计 v1.6 附录 B 错误码表）。
 * 段位：10xxx 通用 / 11xxx 租户 / 12xxx 对象 / 13xxx Action / 14xxx 通道 / 15xxx Agent / 16xxx AI / 17xxx Function。
 */
@Getter
public enum ErrorCode {
    // ---- 10xxx 通用 ----
    OK(0, "ok"),
    PARAM_ERROR(10001, "参数错误"),
    UNAUTHENTICATED(10002, "未认证"),
    FORBIDDEN(10003, "无权限"),
    MFA_FAILED(10004, "MFA 校验失败"),

    // ---- 11xxx 租户 ----
    TENANT_CONTEXT_MISSING(11001, "租户上下文缺失"),
    TENANT_MISMATCH(11002, "租户不匹配"),
    TENANT_DISABLED(11003, "租户已停用"),

    // ---- 12xxx 对象 ----
    OBJECT_NOT_FOUND(12001, "对象不存在"),
    OWNERSHIP_VIOLATION(12002, "归属校验失败"),
    DSL_PARSE_ERROR(12003, "DSL 解析失败"),
    TYPE_UNKNOWN(12004, "类型未知"),
    DYNAMIC_SECURITY_VIOLATION(12005, "动态安全越权"),
    STATIC_MEMBER_REJECTED(12006, "人群模式写成员拒绝"),

    // ---- 13xxx Action ----
    ACTION_NOT_REGISTERED(13001, "Action 未注册"),
    ACTION_VALIDATION_FAILED(13002, "Action 校验失败"),
    IDEMPOTENCY_CONFLICT(13003, "幂等冲突"),
    RATE_LIMITED(13004, "频控限制"),
    UNSUBSCRIBED(13005, "客户已退订"),
    QUOTA_EXCEEDED(13006, "配额超限"),
    QUIET_HOURS(13007, "时段限制"),

    // ---- 14xxx 通道 ----
    CHANNEL_NOT_CONFIGURED(14001, "通道未配置"),
    CHANNEL_UNAVAILABLE(14002, "通道不可用"),
    SEND_FAILED(14003, "发送失败"),
    CALLBACK_SIGNATURE_INVALID(14004, "回执验签失败"),

    // ---- 15xxx Agent ----
    SESSION_NOT_FOUND(15001, "会话不存在"),
    STATE_NOT_ALLOWED(15002, "状态不允许"),
    AWAITING_APPROVAL(15003, "待审批"),

    // ---- 16xxx AI ----
    LLM_CALL_FAILED(16001, "LLM 调用失败"),
    OUTPUT_VALIDATION_FAILED(16002, "输出校验失败"),

    // ---- 17xxx Function ----
    FUNCTION_NOT_REGISTERED(17001, "Function 未注册");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}