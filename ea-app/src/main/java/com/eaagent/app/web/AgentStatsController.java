package com.eaagent.app.web;

import com.eaagent.app.service.AgentStatsService;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 多维度统计（token 构成/耗时分布/成本/cache hit rate/工具与 skill 调用/提示词版本/会话与模型维度）。
 * 维度对齐业界 LLM/agent 可观测性实践（TTFT 近似为 avg_model_ms、cost per run、工具循环检测）。
 */
@RestController
@RequestMapping("/api/agent/stats")
public class AgentStatsController {

    private final AgentStatsService agentStatsService;

    public AgentStatsController(AgentStatsService agentStatsService) {
        this.agentStatsService = agentStatsService;
    }

    @GetMapping
    public Result<Map<String, Object>> stats(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String session_id) {
        return Result.ok(agentStatsService.stats(TenantContext.requiredTenantId(),
                Math.max(1, Math.min(days, 90)), session_id));
    }

    /** Ontology 调用链路图（单独页面）：全量拓扑 + 运行时热点。 */
    @GetMapping("/ontology-graph")
    public Result<Map<String, Object>> ontologyGraph(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String session_id) {
        return Result.ok(agentStatsService.ontologyGraph(TenantContext.requiredTenantId(),
                Math.max(1, Math.min(days, 90)), session_id));
    }

    /** 单次 run 的真实调用链明细（按 seq 升序），供流程图「调用链回放」动效。 */
    @GetMapping("/run-trace")
    public Result<Map<String, Object>> runTrace(@RequestParam("run_id") long runId) {
        return Result.ok(agentStatsService.runTrace(TenantContext.requiredTenantId(), runId));
    }
}