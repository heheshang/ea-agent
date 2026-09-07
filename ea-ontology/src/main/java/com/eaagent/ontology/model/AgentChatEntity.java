package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** agent_chat 表（聊天容器：新建聊天产出唯一 id + 描述；run 与调用链明细按 chat_id 归属，可跨 run 追踪）。 */
@Data
@TableName(value = "agent_chat", autoResultMap = true)
public class AgentChatEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_USER_ID = "user_id";
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_STATUS = "status";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    /** status 值：ACTIVE（本期仅支持创建，不做删除/归档）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 未提供描述时的默认描述；首条 goal 到来时自动更新为 goal 截断，让描述有意义。 */
    public static final String DEFAULT_DESCRIPTION = "新对话";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private String description;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}