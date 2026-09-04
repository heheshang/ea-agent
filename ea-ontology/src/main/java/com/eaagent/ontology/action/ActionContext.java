package com.eaagent.ontology.action;

import java.util.Map;

/**
 * Action 执行上下文（3.4）：租户 / 身份 / 请求幂等键 / 透传属性。
 * 管线第 1-2 步（鉴权、租户）由 AbstractAction 依据 context 完成。
 */
public record ActionContext(
        Long tenantId,
        Long userId,
        String role,
        String requestId,
        Map<String, Object> attributes) {

    public static ActionContext of(Long tenantId, Long userId, String role, String requestId) {
        return new ActionContext(tenantId, userId, role, requestId, Map.of());
    }
}