package com.eaagent.app.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 多维度统计聚合（/api/agent/stats）：
 * 输入 agent_run 全量行（现有规模行级即可，数据量大后再下沉 SQL），Java 侧按维度聚合。
 * 维度对齐业界 LLM/agent 可观测性实践：
 * 耗时/成功率/TTF 近似(模型调用毫秒)、cache hit rate、cost per run、工具调用循环检测、
 * token 输入输出构成、skill 调用、系统提示词版本、会话/模型维度、按日趋势、慢 run TOP。
 * 旧行 usage/tool_calls/cost/prompt_info 为 NULL——以零值/空表兼容。
 */
@Service
public class AgentStatsService {

    private final AgentRunMapper runMapper;

    public AgentStatsService(AgentRunMapper runMapper) {
        this.runMapper = runMapper;
    }

    public Map<String, Object> stats(long tenantId, int days, String sessionId) {
        List<AgentRunEntity> runs = loadRuns(tenantId, days, sessionId);

        // —— 汇总 ——
        int total = runs.size();
        int success = 0, failed = 0;
        long inputTokens = 0, outputTokens = 0, cachedTokens = 0, modelMs = 0, toolCallsTotal = 0, toolCallsFail = 0;
        double costSum = 0, durationSum = 0;
        List<Double> durations = new ArrayList<>();
        Map<String, Long> statusDist = new LinkedHashMap<>();
        // 工具聚合：name -> {calls, fails, msSum, msList}
        Map<String, ToolAgg> tools = new LinkedHashMap<>();
        // 模型聚合：model -> {runs, tokens, cost, ms}
        Map<String, ModelAgg> models = new LinkedHashMap<>();
        // 会话聚合
        Map<String, SessionAgg> sessions = new LinkedHashMap<>();
        // 提示词版本聚合
        Map<String, PromptAgg> prompts = new LinkedHashMap<>();
        // 按日聚合（UTC；业务时区偏差 → 后续可参数化）
        Map<String, DailyAgg> daily = new TreeMap<>();
        // 慢 run 排序候选
        List<Map<String, Object>> slowCandidates = new ArrayList<>();

        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
        for (AgentRunEntity r : runs) {
            String status = r.getStatus() == null ? "UNKNOWN" : r.getStatus();
            statusDist.merge(status, 1L, Long::sum);
            if ("COMPLETED".equals(status)) {
                success++;
            } else if ("FAILED".equals(status)) {
                failed++;
            }
            double duration = r.getCreatedAt() != null && r.getUpdatedAt() != null
                    ? java.time.Duration.between(r.getCreatedAt(), r.getUpdatedAt()).toMillis() / 1000.0 : 0;
            durations.add(duration);
            durationSum += duration;
            slowCandidates.add(Map.of(
                    "id", r.getId(),
                    "goal", trim(r.getGoal(), 60),
                    "status", status,
                    "duration_s", round1(duration),
                    "cost", r.getCost() == null ? 0.0 : r.getCost().doubleValue()));

            String day = r.getCreatedAt() != null ? dayFmt.format(r.getCreatedAt()) : "unknown";
            DailyAgg d = daily.computeIfAbsent(day, k -> new DailyAgg());
            d.runs++;
            d.durationSum += duration;
            double cost = r.getCost() == null ? 0 : r.getCost().doubleValue();

            Map<String, Object> usage = r.getUsage();
            if (usage != null) {
                Number in = num(usage.get("input_tokens"));
                Number out = num(usage.get("output_tokens"));
                Number cache = num(usage.get("cached_tokens"));
                Number mms = num(usage.get("model_ms"));
                String model = str(usage.get("model"), "unknown");
                inputTokens += in.longValue();
                outputTokens += out.longValue();
                cachedTokens += cache.longValue();
                modelMs += mms.longValue();
                d.tokens += in.longValue() + out.longValue();
                ModelAgg ma = models.computeIfAbsent(model, k -> new ModelAgg(model));
                ma.runs++;
                ma.tokens += in.longValue() + out.longValue();
                ma.ms += mms.longValue();
                ma.cost += cost;
            }
            costSum += cost;
            d.cost += cost;

            SessionAgg sa = sessions.computeIfAbsent(r.getSessionId() == null ? "" : r.getSessionId(), k -> new SessionAgg());
            sa.runs++;
            sa.tokens += r.getUsage() == null ? 0
                    : num(r.getUsage().get("input_tokens")).longValue() + num(r.getUsage().get("output_tokens")).longValue();
            sa.cost += cost;
            sa.durationSum += duration;

            if (r.getPromptInfo() != null) {
                String ver = str(r.getPromptInfo().get("sys_prompt_version"), "n/a");
                PromptAgg pa = prompts.computeIfAbsent(ver, k -> new PromptAgg(ver));
                pa.runs++;
                pa.sysPromptLen = num(r.getPromptInfo().get("sys_prompt_len")).intValue();
                pa.cost += cost;
            }

            List<Map<String, Object>> tcs = r.getToolCalls();
            if (tcs != null) {
                for (Map<String, Object> tc : tcs) {
                    String name = str(tc.get("name"), "unknown");
                    ToolAgg ta = tools.computeIfAbsent(name, k -> new ToolAgg(name));
                    ta.calls++;
                    Number ms = num(tc.get("duration_ms"));
                    if (ms != null) {
                        ta.msSum += ms.longValue();
                        ta.msList.add(ms.doubleValue());
                    }
                    boolean ok = Boolean.TRUE.equals(tc.get("ok"));
                    if (!ok) {
                        ta.fails++;
                        toolCallsFail++;
                    }
                    toolCallsTotal++;
                }
            }
        }

        // —— p95 耗时 ——
        double p95 = durations.isEmpty() ? 0 : percentile(durations, 0.95);
        double avgDuration = total == 0 ? 0 : durationSum / total;
        double cacheHitRate = inputTokens == 0 ? 0 : (double) cachedTokens / inputTokens;
        double tps = durationSum <= 0 ? 0 : outputTokens / durationSum;
        double avgModelMs = total == 0 ? 0 : modelMs / (double) total;
        double avgCostPerRun = total == 0 ? 0 : costSum / total;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runs", total);
        summary.put("success_runs", success);
        summary.put("failed_runs", failed);
        summary.put("success_rate", total == 0 ? 0 : round4((double) success / total));
        summary.put("total_tokens", inputTokens + outputTokens);
        summary.put("input_tokens", inputTokens);
        summary.put("output_tokens", outputTokens);
        summary.put("cached_tokens", cachedTokens);
        summary.put("cache_hit_rate", round4(cacheHitRate));
        summary.put("total_cost", round4(costSum));
        summary.put("cost_per_run", round4(avgCostPerRun));
        summary.put("avg_duration_s", round1(avgDuration));
        summary.put("p95_duration_s", round1(p95));
        summary.put("max_duration_s", durations.isEmpty() ? 0 : round1(durations.stream().max(Double::compareTo).orElse(0.0)));
        summary.put("avg_model_ms", round0(avgModelMs));
        summary.put("avg_tps", round1(tps));
        summary.put("tool_calls_total", toolCallsTotal);
        summary.put("tool_fail_total", toolCallsFail);

        // —— 输出结构化维度 ——
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("status_dist", statusDist.entrySet().stream()
                .map(e -> Map.of("status", e.getKey(), "count", e.getValue())).toList());
        out.put("daily", daily.entrySet().stream()
                .map(e -> {
                    DailyAgg v = e.getValue();
                    return Map.of("date", e.getKey(), "runs", v.runs, "tokens", v.tokens,
                            "cost", round4(v.cost), "avg_duration_s", v.runs == 0 ? 0 : round1(v.durationSum / v.runs));
                }).toList());
        out.put("tools", tools.values().stream()
                .sorted(Comparator.comparingLong((ToolAgg t) -> t.calls).reversed())
                .map(ToolAgg::toMap)
                .limit(10).toList());
        out.put("skills", tools.values().stream()
                .filter(t -> t.name.contains("skill"))
                .map(ToolAgg::toMap).toList());
        out.put("models", models.values().stream()
                .map(m -> Map.of("model", m.model, "runs", m.runs, "tokens", m.tokens,
                        "cost", round4(m.cost), "avg_model_ms", m.runs == 0 ? 0 : round0(m.ms / (double) m.runs)))
                .toList());
        out.put("sessions", sessions.entrySet().stream()
                .map(e -> {
                    SessionAgg v = e.getValue();
                    return Map.of("session_id", e.getKey(), "runs", v.runs, "tokens", v.tokens,
                            "cost", round4(v.cost),
                            "avg_duration_s", v.runs == 0 ? 0 : round1(v.durationSum / v.runs));
                })
                .sorted((a, b) -> Long.compare(((Number) b.get("runs")).longValue(),
                        ((Number) a.get("runs")).longValue()))
                .limit(10).toList());
        out.put("prompt_versions", prompts.values().stream()
                .map(p -> Map.of("version", p.version, "runs", p.runs, "sys_prompt_len", p.sysPromptLen, "cost", round4(p.cost)))
                .toList());
        out.put("top_slow_runs", slowCandidates.stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("duration_s")).doubleValue()).reversed())
                .limit(5).toList());
        return out;
    }

    /**
     * Ontology 调用链路图（/api/agent/stats/ontology-graph）：
     * 分层拓扑（引擎 → 工具 → Action/Function → 对象）+ 运行时调用热点。
     * 节点/边 = 代码事实（TypeRegistry 7 对象、ActionRegistry 6 Action、FunctionRegistry 5 Function、
     * AgentToolRegistry 7 工具），运行时统计从 agent_run.tool_calls 聚合：引擎→工具边按工具调用，
     * tool:applyAction→Action 边按入参 action 拆分，tool:callFunction→Function 边按入参 name 拆分。
     */
    public Map<String, Object> ontologyGraph(long tenantId, int days, String sessionId) {
        List<AgentRunEntity> runs = loadRuns(tenantId, days, sessionId);

        // 静态拓扑映射（代码事实：查询工具→对象、Action→主写对象、Function→主读对象）
        Map<String, String> toolToObject = new LinkedHashMap<>();
        toolToObject.put("queryCustomers", "customer");
        toolToObject.put("queryAudience", "audience");
        toolToObject.put("getCampaign", "campaign");
        toolToObject.put("queryDelivery", "delivery");
        toolToObject.put("queryEvents", "event");
        List<String> tools = new ArrayList<>(toolToObject.keySet());
        tools.add("applyAction");
        tools.add("callFunction");

        Map<String, String> actionToObject = new LinkedHashMap<>();
        actionToObject.put("sendTouch", "delivery");
        actionToObject.put("createCampaign", "campaign");
        actionToObject.put("pauseCampaign", "campaign");
        actionToObject.put("updateCampaign", "campaign");
        actionToObject.put("updateCustomerState", "customer");
        actionToObject.put("importEvents", "event");

        Map<String, String> functionToObject = new LinkedHashMap<>();
        functionToObject.put("audienceStats", "audience");
        functionToObject.put("frequencyCheck", "delivery");
        functionToObject.put("channelPreference", "customer");
        functionToObject.put("churnRiskScore", "event");
        functionToObject.put("bestSendTime", "delivery");

        Map<String, String> objectLabels = new LinkedHashMap<>();
        objectLabels.put("customer", "Customer 客户");
        objectLabels.put("audience", "Audience 人群");
        objectLabels.put("campaign", "Campaign 活动");
        objectLabels.put("template", "Template 模板");
        objectLabels.put("channel", "Channel 通道");
        objectLabels.put("delivery", "Delivery 触达记录");
        objectLabels.put("event", "Event 事件");

        // 运行时聚合
        Map<String, ToolAgg> toolAggs = new LinkedHashMap<>();
        Map<String, ToolAgg> actionAggs = new LinkedHashMap<>();
        Map<String, ToolAgg> functionAggs = new LinkedHashMap<>();
        for (AgentRunEntity r : runs) {
            List<Map<String, Object>> tcs = r.getToolCalls();
            if (tcs == null) {
                continue;
            }
            for (Map<String, Object> tc : tcs) {
                String name = str(tc.get("name"), "unknown");
                ToolAgg agg;
                if ("applyAction".equals(name)) {
                    String actName = parseAction(tc.get("params"));
                    if (actName == null) {
                        continue;
                    }
                    agg = actionAggs.computeIfAbsent(actName, ToolAgg::new);
                } else if ("callFunction".equals(name)) {
                    String fnName = parseFunction(tc.get("params"));
                    if (fnName == null) {
                        continue;
                    }
                    agg = functionAggs.computeIfAbsent(fnName, ToolAgg::new);
                } else {
                    agg = toolAggs.computeIfAbsent(name, ToolAgg::new);
                }
                agg.calls++;
                Number ms = num(tc.get("duration_ms"));
                agg.msSum += ms.longValue();
                agg.msList.add(ms.doubleValue());
                if (!Boolean.TRUE.equals(tc.get("ok"))) {
                    agg.fails++;
                }
            }
        }

        // 节点
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(Map.of("id", "engine", "type", "engine", "label", "Agent 引擎",
                "calls", runs.size(), "avg_ms", 0, "fails", 0));
        for (String t : tools) {
            ToolAgg a = toolAggs.get(t);
            nodes.add(a == null ? Map.of("id", "tool:" + t, "type", "tool", "label", t)
                    : Map.of("id", "tool:" + t, "type", "tool", "label", t,
                    "calls", a.calls, "avg_ms", a.calls == 0 ? 0 : round0(a.msSum / (double) a.calls), "fails", a.fails));
        }
        for (String act : actionToObject.keySet()) {
            ToolAgg a = actionAggs.get(act);
            nodes.add(a == null ? Map.of("id", "action:" + act, "type", "action", "label", act)
                    : Map.of("id", "action:" + act, "type", "action", "label", act,
                    "calls", a.calls, "avg_ms", a.calls == 0 ? 0 : round0(a.msSum / (double) a.calls), "fails", a.fails));
        }
        for (String fn : functionToObject.keySet()) {
            ToolAgg a = functionAggs.get(fn);
            nodes.add(a == null ? Map.of("id", "function:" + fn, "type", "function", "label", fn)
                    : Map.of("id", "function:" + fn, "type", "function", "label", fn,
                    "calls", a.calls, "avg_ms", a.calls == 0 ? 0 : round0(a.msSum / (double) a.calls), "fails", a.fails));
        }
        for (String obj : objectLabels.keySet()) {
            nodes.add(Map.of("id", "obj:" + obj, "type", "object", "label", objectLabels.get(obj)));
        }

        // 边
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String t : tools) {
            ToolAgg a = toolAggs.get(t);
            edges.add(a == null
                    ? Map.of("from", "engine", "to", "tool:" + t)
                    : Map.of("from", "engine", "to", "tool:" + t,
                    "calls", a.calls, "avg_ms", a.calls == 0 ? 0 : round0(a.msSum / (double) a.calls), "fails", a.fails));
        }
        for (String act : actionToObject.keySet()) {
            ToolAgg a = actionAggs.get(act);
            edges.add(a == null
                    ? Map.of("from", "tool:applyAction", "to", "action:" + act)
                    : Map.of("from", "tool:applyAction", "to", "action:" + act,
                    "calls", a.calls, "avg_ms", a.calls == 0 ? 0 : round0(a.msSum / (double) a.calls), "fails", a.fails));
        }
        for (String fn : functionToObject.keySet()) {
            ToolAgg a = functionAggs.get(fn);
            edges.add(a == null
                    ? Map.of("from", "tool:callFunction", "to", "function:" + fn)
                    : Map.of("from", "tool:callFunction", "to", "function:" + fn,
                    "calls", a.calls, "avg_ms", a.calls == 0 ? 0 : round0(a.msSum / (double) a.calls), "fails", a.fails));
        }
        for (String t : toolToObject.keySet()) {
            String obj = toolToObject.get(t);
            edges.add(Map.of("from", "tool:" + t, "to", "obj:" + obj));
        }
        for (String act : actionToObject.keySet()) {
            edges.add(Map.of("from", "action:" + act, "to", "obj:" + actionToObject.get(act)));
        }
        for (String fn : functionToObject.keySet()) {
            edges.add(Map.of("from", "function:" + fn, "to", "obj:" + functionToObject.get(fn)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    private List<AgentRunEntity> loadRuns(long tenantId, int days, String sessionId) {
        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        QueryWrapper<AgentRunEntity> qw = new QueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_TENANT_ID, tenantId)
                .ge(AgentRunEntity.COL_CREATED_AT, from)
                .orderByDesc(AgentRunEntity.COL_ID);
        if (sessionId != null && !sessionId.isBlank()) {
            qw.eq(AgentRunEntity.COL_SESSION_ID, sessionId);
        }
        return runMapper.selectList(qw);
    }

    /** 从 applyAction 调用参数中解析动作名（兼容 JSON 与 Java map toString 两种形态）。 */
    private static String parseAction(Object params) {
        if (params == null) {
            return null;
        }
        String s = String.valueOf(params).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> m = JsonUtils.readMap(s);
            Object a = m.get("action");
            if (a != null) {
                return String.valueOf(a);
            }
        } catch (Exception ignored) {
            // 非 JSON（Java map toString），走下方正则
        }
        int i = s.indexOf("action=");
        if (i >= 0) {
            String rest = s.substring(i + "action=".length()).trim();
            int j = rest.indexOf(',');
            if (j > 0) {
                return rest.substring(0, j).trim();
            }
            j = rest.indexOf('}');
            if (j > 0) {
                return rest.substring(0, j).trim();
            }
        }
        return null;
    }

    /** 从 callFunction 调用参数中解析函数名（兼容 JSON 与 Java map toString 两种形态）。 */
    private static String parseFunction(Object params) {
        if (params == null) {
            return null;
        }
        String s = String.valueOf(params).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> m = JsonUtils.readMap(s);
            Object n = m.get("name");
            if (n != null) {
                return String.valueOf(n);
            }
        } catch (Exception ignored) {
            // 非 JSON（Java map toString），走下方正则
        }
        int i = s.indexOf("name=");
        if (i >= 0) {
            String rest = s.substring(i + "name=".length()).trim();
            int j = rest.indexOf(',');
            if (j > 0) {
                return rest.substring(0, j).trim();
            }
            j = rest.indexOf('}');
            if (j > 0) {
                return rest.substring(0, j).trim();
            }
        }
        return null;
    }

    private static double percentile(List<Double> sorted, double q) {
        sorted.sort(Double::compareTo);
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static Number num(Object o) {
        return o instanceof Number n ? n : 0L;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static String trim(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round0(double v) {
        return Math.round(v);
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static final class ToolAgg {
        final String name;
        long calls = 0;
        long fails = 0;
        long msSum = 0;
        final List<Double> msList = new ArrayList<>();

        ToolAgg(String name) {
            this.name = name;
        }

        Map<String, Object> toMap() {
            double avgMs = calls == 0 ? 0 : msSum / (double) calls;
            List<Double> tmp = new ArrayList<>(msList);
            tmp.sort(Double::compareTo);
            double p95 = tmp.isEmpty() ? 0 : tmp.get(Math.max(0, (int) Math.ceil(0.95 * tmp.size()) - 1));
            return Map.of("name", name, "calls", calls, "fails", fails,
                    "fail_rate", calls == 0 ? 0 : round4((double) fails / calls),
                    "avg_ms", round0(avgMs), "p95_ms", round0(p95));
        }
    }

    private static final class ModelAgg {
        final String model;
        long runs = 0;
        long tokens = 0;
        long ms = 0;
        double cost = 0;

        ModelAgg(String model) {
            this.model = model;
        }
    }

    private static final class SessionAgg {
        long runs = 0;
        long tokens = 0;
        double cost = 0;
        double durationSum = 0;
    }

    private static final class PromptAgg {
        final String version;
        long runs = 0;
        int sysPromptLen = 0;
        double cost = 0;

        PromptAgg(String version) {
            this.version = version;
        }
    }

    private static final class DailyAgg {
        long runs = 0;
        long tokens = 0;
        double cost = 0;
        double durationSum = 0;
    }
}