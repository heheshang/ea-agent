package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** agent_scope_state 表（agentscope AgentStateStore 会话状态 KV：schema 4.1）。 */
@Data

@TableName(value = "agent_scope_state", autoResultMap = true)
public class AgentScopeStateEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_USER_ID = "user_id";
    public static final String COL_SESSION_ID = "session_id";
    public static final String COL_STATE_KEY = "state_key";
    public static final String COL_SLOT_KIND = "slot_kind";
    public static final String COL_CONTENT = "content";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    /** slot_kind 值：single=单对象 JSON；list=对象数组 JSON（全量替换，对齐 InMemory 契约）。 */
    public static final String SLOT_SINGLE = "single";
    public static final String SLOT_LIST = "list";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** 引擎传入的 userId（多租户标识：tenant-{tenantId}）。 */
    private String userId;
    private String sessionId;
    private String stateKey;
    /** single|list。 */
    private String slotKind;
    /** 状态内容 jsonb（single=单对象；list=对象数组）。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object content;
    private Instant createdAt;
    private Instant updatedAt;
}