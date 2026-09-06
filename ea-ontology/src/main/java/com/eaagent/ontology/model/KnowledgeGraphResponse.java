package com.eaagent.ontology.model;

import com.eaagent.ontology.model.KnowledgeEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 知识图谱响应（V15）：租户全部条目（节点，含停用/被取代/废弃——前端灰显区分）
 *  + 关系边（supersedes 取代链边 + knowledge_link 类型化边合并）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeGraphResponse {
    private List<KnowledgeEntity> nodes;
    private List<Edge> edges;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {
        private Long source;      // 起点条目 id
        private Long target;      // 终点条目 id
        private String relation;  // supersedes/related/supports/refines/conflicts
        private Long linkId;      // knowledge_link 行 id（supersedes 边为 null，删边走条目编辑）
    }
}