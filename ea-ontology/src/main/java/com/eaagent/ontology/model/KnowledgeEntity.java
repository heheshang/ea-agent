package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** knowledge 表：租户级知识库条目，Agent 对话时按相关度注入上下文（检索逻辑见 KnowledgeBaseService）。 */
@Data

@TableName(value = "knowledge", autoResultMap = true)
public class KnowledgeEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_TITLE = "title";
    public static final String COL_CONTENT = "content";
    public static final String COL_TAGS = "tags";
    public static final String COL_ENABLED = "enabled";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_RECORD_TYPE = "record_type";
    public static final String COL_LIFECYCLE = "lifecycle";
    public static final String COL_SUPERSEDES_ID = "supersedes_id";

    /** 记录类别(V14 本体化;缺省 rule 兼容存量)。 */
    public static final String TYPE_DECISION = "decision";        // 架构/业务决策
    public static final String TYPE_CONSTRAINT = "constraint";    // 硬性约束(触达前必须核对)
    public static final String TYPE_RULE = "rule";                // 业务规则(默认)
    public static final String TYPE_LESSON = "lesson";            // 经验教训
    public static final String TYPE_RATIONALE = "rationale";      // 决策理由
    public static final String TYPE_FACT = "fact";                // 事实
    public static final String TYPE_ANTI_PATTERN = "anti_pattern"; // 反模式

    /** 生命周期(V14;active 现行 / superseded 被取代 / obsolete 废弃)。 */
    public static final String LIFE_ACTIVE = "active";
    public static final String LIFE_SUPERSEDED = "superseded";
    public static final String LIFE_OBSOLETE = "obsolete";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String title;
    private String content;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;        // jsonb 字符串数组
    private Boolean enabled;          // 停用后不参与检索
    private String recordType;        // decision/constraint/rule/lesson/rationale/fact/anti_pattern
    private String lifecycle;         // active/superseded/obsolete
    private Long supersedesId;        // 取代边:本条取代哪条(新→旧)
    private Instant createdAt;
    private Instant updatedAt;
}