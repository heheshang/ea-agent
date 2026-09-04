package com.eaagent.common;

import java.util.Optional;

/**
 * 租户上下文（详细设计 5.1）：ThreadLocal 承载当前请求的租户与身份。
 * 跨线程传递由 TaskDecorator 完成（ea-app 装配）；EA-Bus 消费端按消息 tenant_id 重建（5.5）。
 * 隔离手段 = 复合 FK + 应用层 TenantContext + 显式 tenant_id；禁用租户插件。
 */
public final class TenantContext {
    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        TENANT.set(tenantId);
    }

    public static void setIdentity(Long tenantId, Long userId, String role) {
        TENANT.set(tenantId);
        USER.set(userId);
        ROLE.set(role);
    }

    /** 缺失上下文抛 E-11001（租户上下文缺失）。 */
    public static long requiredTenantId() {
        return Optional.ofNullable(TENANT.get())
                .orElseThrow(() -> new BizException(ErrorCode.TENANT_CONTEXT_MISSING));
    }

    public static Long tenantId() {
        return TENANT.get();
    }

    public static Long userId() {
        return USER.get();
    }

    public static String role() {
        return ROLE.get();
    }

    /** 完整拷贝（TaskDecorator 使用），返回 null 表示当前无上下文。 */
    public static Context snapshot() {
        Long tenant = TENANT.get();
        if (tenant == null) {
            return null;
        }
        return new Context(tenant, USER.get(), ROLE.get());
    }

    public static void restore(Context ctx) {
        if (ctx == null) {
            clear();
            return;
        }
        TENANT.set(ctx.tenantId());
        USER.set(ctx.userId());
        ROLE.set(ctx.role());
    }

    public static void clear() {
        TENANT.remove();
        USER.remove();
        ROLE.remove();
    }

    /** 不可变快照，供 TaskDecorator / 异步边界携带。 */
    public record Context(Long tenantId, Long userId, String role) {
    }
}