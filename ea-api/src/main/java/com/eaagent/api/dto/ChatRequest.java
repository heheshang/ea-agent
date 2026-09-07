package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Agent 对话发起请求（10.2：建 agent_run + AgentSession，不等流）。 */
@Data
public class ChatRequest {
    @NotBlank
    private String goal;
    /** 会话模式：auto（直接执行，默认）| suggest（写动作挂起人工审批，见 ea:agent:mode 会话级键）。 */
    private String mode;
    /** 归属聊天 id（V17 新建聊天：缺省时服务端按 goal 自动建聊天并返回 chat_id，保证调用链可追踪）。 */
    private Long chatId;
}