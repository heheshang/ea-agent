package com.eaagent.app.web;

import com.eaagent.agent.service.KnowledgeBaseService;
import com.eaagent.api.dto.KnowledgeLinkWriteRequest;
import com.eaagent.api.dto.KnowledgeWriteRequest;
import com.eaagent.common.PageResult;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.model.KnowledgeEntity;
import com.eaagent.ontology.model.KnowledgeGraphResponse;
import com.eaagent.ontology.model.KnowledgeLinkEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口（/api/knowledge）：租户维度 CRUD + 检索预览。
 * 检索与 Agent 对话注入同源（KnowledgeBaseService 打分），供管理页试检索验证相关度。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeBaseService knowledgeService;

    public KnowledgeController(KnowledgeBaseService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public Result<PageResult<KnowledgeEntity>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(knowledgeService.list(TenantContext.requiredTenantId(), keyword,
                recordType, lifecycle, page, size));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeEntity> get(@PathVariable Long id) {
        return Result.ok(knowledgeService.get(TenantContext.requiredTenantId(), id));
    }

    /** 取代链遍历：该条目的完整版本演进（最旧 → 最新，含 superseded 中间态），供管理页查看。 */
    @GetMapping("/{id}/trace")
    public Result<List<KnowledgeEntity>> trace(@PathVariable Long id) {
        return Result.ok(knowledgeService.trace(TenantContext.requiredTenantId(), id));
    }

    /** 检索预览：同 Agent 注入的确定性打分，返回 top 命中（含得分），top_k 默认 3。
     *  默认仅现行条目（active_only=true，与注入同源）；传 active_only=false 可连被取代/废弃条目一起预览。 */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam String q,
                                                    @RequestParam(defaultValue = "3") int top_k,
                                                    @RequestParam(defaultValue = "true") boolean active_only) {
        List<KnowledgeBaseService.KnowledgeHit> hits =
                knowledgeService.searchScored(TenantContext.requiredTenantId(), q, top_k, !active_only);
        List<Map<String, Object>> out = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.entry().getId());
            m.put("title", h.entry().getTitle());
            m.put("tags", h.entry().getTags());
            m.put("recordType", h.entry().getRecordType());
            m.put("lifecycle", h.entry().getLifecycle());
            m.put("score", h.score());
            return m;
        }).toList();
        return Result.ok(out);
    }

    /** 知识图谱（V15）：租户全部条目（节点）+ 关系边（supersedes 取代边 + 类型化边合并），前端力导布局渲染。 */
    @GetMapping("/graph")
    public Result<KnowledgeGraphResponse> graph() {
        return Result.ok(knowledgeService.graph(TenantContext.requiredTenantId()));
    }

    /** 新建关系边：source → target 类型化边（related/supports/refines/conflicts；同向同类型不重复）。 */
    @PostMapping("/links")
    public Result<KnowledgeLinkEntity> createLink(@RequestBody KnowledgeLinkWriteRequest req) {
        return Result.ok(knowledgeService.createLink(TenantContext.requiredTenantId(),
                req.getSourceId(), req.getTargetId(), req.getRelationType()));
    }

    /** 删除关系边（仅类型化边；supersedes 边随条目编辑变更）。 */
    @DeleteMapping("/links/{id}")
    public Result<Void> deleteLink(@PathVariable Long id) {
        knowledgeService.deleteLink(TenantContext.requiredTenantId(), id);
        return Result.ok(null);
    }

    @PostMapping
    public Result<KnowledgeEntity> create(@RequestBody KnowledgeWriteRequest req) {
        return Result.ok(knowledgeService.create(TenantContext.requiredTenantId(), req));
    }

    /** 编辑：传入覆盖、缺失保留。 */
    @PutMapping("/{id}")
    public Result<KnowledgeEntity> update(@PathVariable Long id, @RequestBody KnowledgeWriteRequest req) {
        return Result.ok(knowledgeService.update(TenantContext.requiredTenantId(), id, req));
    }

    /** 物理删除（表无 deleted 列）。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(TenantContext.requiredTenantId(), id);
        return Result.ok(null);
    }
}