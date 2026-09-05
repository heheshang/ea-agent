package com.eaagent.common;

import java.util.Map;
import java.util.Set;

/**
 * 角色常量（9.2 权限体系）。角色分级见 {@link #ROLE_LEVEL}；ADMIN_ROLES 为对象 API 的管理员视图角色集。
 */
public final class Roles {
    public static final String OPERATOR = "OPERATOR";
    public static final String REVIEWER = "REVIEWER";
    public static final String ADMIN = "ADMIN";
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** 角色 → 权限级别（checkPermission 分级：级别 ≥ 要求级别即可执行）。 */
    public static final Map<String, Integer> ROLE_LEVEL = Map.of(
            OPERATOR, 1, REVIEWER, 2, ADMIN, 3, PLATFORM_ADMIN, 4);

    /** 对象 API 可视作管理员（绕出租户归属校验）的角色集。 */
    public static final Set<String> ADMIN_ROLES = Set.of(REVIEWER, ADMIN, PLATFORM_ADMIN);

    private Roles() {
    }
}
