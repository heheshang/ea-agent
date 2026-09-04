package com.eaagent.agent.event;

/**
 * 引擎流事件（引擎 → AgentService → SSE 的中间表示）。
 * type ∈ plan|thinking_delta|tool_call|approval_required|action_result|text_delta|done
 */
public record EngineEvent(String type, String data) {
}