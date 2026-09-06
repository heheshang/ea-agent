package com.eaagent.api.dto;

import lombok.Data;

/** 知识图谱关系边创建请求（V15）：source 条目 → target 条目的类型化边。
 *  关系类型 related/supports/refines/conflicts（supersedes 走条目 supersedesId，不走本表）。 */
@Data
public class KnowledgeLinkWriteRequest {
    private Long sourceId;      // 起点条目 id（同租户必存在、不能自连）
    private Long targetId;      // 终点条目 id（同租户必存在）
    private String relationType; // related/supports/refines/conflicts
}