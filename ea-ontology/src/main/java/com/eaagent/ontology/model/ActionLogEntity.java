package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** action_log 表（Action 管线第 6 步全量审计；参数脱敏，不可变无更新 API —— 9.4）。 */
@Data

@TableName(value = "action_log", autoResultMap = true)
public class ActionLogEntity {
    public static final String COL_ID = "id";
    public static final String COL_REQUEST_ID = "request_id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_ACTOR_TYPE = "actor_type";
    public static final String COL_ACTOR_ID = "actor_id";
    public static final String COL_ACTION = "action";
    public static final String COL_ARGS = "args";
    public static final String COL_RESULT = "result";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private Long tenantId;
    private String actorType;    // USER|AGENT|SYSTEM
    private String actorId;
    private String action;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> args;    // 脱敏后
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> result;
    private Instant createdAt;
}