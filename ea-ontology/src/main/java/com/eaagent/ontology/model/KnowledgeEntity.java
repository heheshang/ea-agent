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

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String title;
    private String content;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;        // jsonb 字符串数组
    private Boolean enabled;          // 停用后不参与检索
    private Instant createdAt;
    private Instant updatedAt;
}