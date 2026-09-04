package com.eaagent.agent.engine;

/**
 * 引擎运行时上下文：租户/身份与会话标识（会话级隔离）。
 */
public record RunContext(Long tenantId, Long userId, String role, String sessionId, String runId) {
}