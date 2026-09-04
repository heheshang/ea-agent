package com.eaagent.api.sse;

import com.eaagent.common.JsonUtils;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 会话事件（详细设计 4.6 SSE 协议）：event 名称 + data 字段。
 * 事件名：plan / thinking_delta / tool_call / approval_required / action_result / text_delta / done。
 */
@Getter
public class AgentSseEvent {
    private final String event;
    private final Map<String, Object> data;

    public AgentSseEvent(String event, Map<String, Object> data) {
        this.event = event;
        this.data = data;
    }

    public static AgentSseEvent of(String event, String runId, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        if (data != null) {
            m.putAll(data);
        }
        return new AgentSseEvent(event, m);
    }

    public String toSseFrame() {
        return "event: " + event + "\ndata: " + JsonUtils.write(data) + "\n\n";
    }
}