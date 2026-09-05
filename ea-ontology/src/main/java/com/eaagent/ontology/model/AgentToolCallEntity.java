package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** agent_tool_call 表（调用链明细：run 内工具调用顺序，供回放/审计；引擎完成时批量写入）。 */
@Data
@TableName(value = "agent_tool_call", autoResultMap = true)
public class AgentToolCallEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_RUN_ID = "run_id";
    public static final String COL_SEQ = "seq";
    public static final String COL_KIND = "kind";
    public static final String COL_NAME = "name";
    public static final String COL_TARGET = "target";
    public static final String COL_ARGS = "args";
    public static final String COL_DURATION_MS = "duration_ms";
    public static final String COL_OK = "ok";
    public static final String COL_ERROR = "error";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long runId;
    /** run 内调用序号（1 起，ToolResultEnd 完成顺序）。 */
    private Integer seq;
    /** tool | action | function（target 非空时为 action/function）。 */
    private String kind;
    /** 工具名（如 applyAction / callFunction / queryCustomers）。 */
    private String name;
    /** 入参解析出的动作/函数名（查询工具为 null）。 */
    private String target;
    /** 参数摘要 JSON 文本（与 agent_run.tool_calls.params 同源截断）。 */
    private String args;
    private Integer durationMs;
    private Boolean ok;
    private String error;
    private Instant createdAt;
}