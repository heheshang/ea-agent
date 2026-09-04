package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Agent 对话发起请求（10.2：建 agent_run + AgentSession，不等流）。 */
@Data
public class ChatRequest {
    @NotBlank
    private String goal;
}