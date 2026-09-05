package com.eaagent.app.web;

import com.eaagent.agent.service.KnowledgeBaseService;
import com.eaagent.api.dto.KnowledgeWriteRequest;
import com.eaagent.common.PageResult;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.model.KnowledgeEntity;
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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(knowledgeService.list(TenantContext.requiredTenantId(), keyword, page, size));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeEntity> get(@PathVariable Long id) {
        return Result.ok(knowledgeService.get(TenantContext.requiredTenantId(), id));
    }

    /** 检索预览：同 Agent 注入的确定性打分，返回 top 命中（含得分），top_k 默认 3。 */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam String q,
                                                    @RequestParam(defaultValue = "3") int top_k) {
        List<KnowledgeBaseService.KnowledgeHit> hits =
                knowledgeService.searchScored(TenantContext.requiredTenantId(), q, top_k);
        List<Map<String, Object>> out = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.entry().getId());
            m.put("title", h.entry().getTitle());
            m.put("tags", h.entry().getTags());
            m.put("score", h.score());
            return m;
        }).toList();
        return Result.ok(out);
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