package com.eaagent.api.dto;

import lombok.Data;

/** 新建聊天请求（V17：POST /api/agent/chats，产出唯一 id + 描述；描述缺省为「新对话」）。 */
@Data
public class ChatCreateRequest {
    /** 聊天描述（可空：缺省「新对话」，首条 goal 到来时自动更新为 goal 截断）。 */
    private String description;
}