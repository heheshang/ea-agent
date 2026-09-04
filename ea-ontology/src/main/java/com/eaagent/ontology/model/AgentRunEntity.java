package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** agent_run 表（会话运行痕迹：plan/decisions 全量审计回放；状态机 4.3）。 */
@Data

@TableName(value = "agent_run", autoResultMap = true)
public class AgentRunEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_SESSION_ID = "session_id";
    public static final String COL_USER_ID = "user_id";
    public static final String COL_GOAL = "goal";
    public static final String COL_PLAN = "plan";
    public static final String COL_DECISIONS = "decisions";
    public static final String COL_STATUS = "status";
    public static final String COL_SUMMARY = "summary";
    public static final String COL_USAGE = "usage";
    public static final String COL_TOOL_CALLS = "tool_calls";
    public static final String COL_COST = "cost";
    public static final String COL_PROMPT_INFO = "prompt_info";
    public static final String COL_TOKENS_USED = "tokens_used";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String sessionId;
    private Long userId;
    private String goal;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.List<java.util.Map<String, Object>> plan;    // jsonb
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.List<java.util.Map<String, Object>> decisions; // jsonb
    private String status;         // NEW|PLANNING|AWAITING_APPROVAL|EXECUTING|OBSERVING|COMPLETED|FAILED|CANCELLED
    private String summary;        // 模型最终回复摘要（记忆注入材料，完成时回写）
    /** 模型调用 token 明细 jsonb：{"model","input_tokens","output_tokens","cached_tokens","model_calls","model_ms"}。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> usage;
    /** 工具调用明细 jsonb：[{"name","params","duration_ms","ok","error"}]，含 skill 加载调用。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.List<java.util.Map<String, Object>> toolCalls;
    /** 按模型单价估算的花费（美元）。 */
    private java.math.BigDecimal cost;
    /** 提示词信息 jsonb：{"sys_prompt_version","sys_prompt_len","memory_review_count","input_len"}。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> promptInfo;
    private Long tokensUsed;
    private Instant createdAt;
    private Instant updatedAt;
}