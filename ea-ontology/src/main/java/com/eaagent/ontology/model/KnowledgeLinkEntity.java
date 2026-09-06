package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** knowledge_link 表：知识图谱类型化关系边（V15），source → target 定向边。
 *  supersedes(取代) 不在本表——由 knowledge.supersedes_id 表达（取代链 + 生命周期联动），图谱查询合并两者。 */
@Data
@TableName("knowledge_link")
public class KnowledgeLinkEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_SOURCE_ID = "source_id";
    public static final String COL_TARGET_ID = "target_id";
    public static final String COL_RELATION_TYPE = "relation_type";
    public static final String COL_CREATED_AT = "created_at";

    /** 关系类型（V15 白名单；related 相关 / supports 支撑 / refines 细化 / conflicts 冲突）。 */
    public static final String REL_RELATED = "related";
    public static final String REL_SUPPORTS = "supports";
    public static final String REL_REFINES = "refines";
    public static final String REL_CONFLICTS = "conflicts";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long sourceId;        // 起点条目
    private Long targetId;        // 终点条目
    private String relationType;  // related/supports/refines/conflicts
    private Instant createdAt;
}