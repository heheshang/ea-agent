package com.eaagent.agent.engine;

/**
 * 引擎运行时上下文：租户/身份与会话标识（会话级隔离）。
 * chatId（V17 新建聊天）：调用链明细（agent_tool_call）按聊天归属，可跨 run 追踪；旧流程为 null。
 */
public record RunContext(Long tenantId, Long userId, String role, String sessionId, String runId, Long chatId) {
}