package com.eaagent.agent.engine;

import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.mapper.AgentToolCallMapper;
import com.eaagent.ontology.model.AgentToolCallEntity;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 多维度统计采集器（挂载为 HarnessAgent 中间件）：
 * <ul>
 *   <li>onModelCall：监听 ModelCallEndEvent，累加每次模型调用的 ChatUsage
 *       （输入/输出/缓存命中 token + 调用耗时）——真实 token 来源（此前 tokens_used 恒为 0）；</li>
 *   <li>onActing：监听 ToolResultStart/EndEvent，记录工具调用名称/参数摘要/耗时/成败
 *       （含 skill 加载工具 load_skill_through_path，即 skill 调用维度）。</li>
 * </ul>
 * 生命周期：按 session 与 HarnessAgent 一一对应；每次 stream 前 {@link #begin}，
 * 完成后 {@link #drainUsage}/{@link #drainToolCalls} 单次取走（同 session 串行执行，无并发）。
 */
public class RunStatsMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(RunStatsMiddleware.class);

    /** 单次工具调用参数序列化截断长度（防 jsonb 膨胀）。 */
    private static final int PARAMS_LIMIT = 300;
    /** 单次 run 工具调用最大记录数（防失控循环刷爆 jsonb）。 */
    private static final int TOOL_CALLS_LIMIT = 200;

    private final String modelName;
    /** 会话 id（仅日志关联用）。 */
    private final String sessionId;
    /** 调用链明细落库（运行中实时写；完成时引擎按 seq 去重兜底）。 */
    private final AgentToolCallMapper toolCallMapper;
    /** 当前 run 上下文（begin 时绑定；供运行中实时落库调用链明细）。 */
    private long tenantId;
    private long runId;
    private final List<UsageAcc> usages = new ArrayList<>();
    private final List<Map<String, Object>> toolCalls = new ArrayList<>();
    /** run 内工具调用序号（ToolResultEnd 完成顺序，1 起；供调用链回放）。 */
    private int toolSeq = 0;
    /** 当前 run 的会话回顾注入条数（引擎 begin 时写入，落 prompt_info）。 */
    private int reviewCount = 0;
    /** 当前 run 的知识库注入条数（引擎 begin 时写入，落 prompt_info）。 */
    private int kbHits = 0;
    /** 知识库检索步骤（run 首步 seq=1；unmatched 未命中时也保留步骤，ok=false）。 */
    private Map<String, Object> kbRow;

    // onActing 计时/匹配表：以 toolCallId 关联 Start 与 End
    private final Map<String, Long> actingStartNanos = new HashMap<>();
    private final Map<String, ToolUseBlock> actingCalls = new HashMap<>();

    public RunStatsMiddleware(String modelName, String sessionId, AgentToolCallMapper toolCallMapper) {
        this.modelName = modelName;
        this.sessionId = sessionId;
        this.toolCallMapper = toolCallMapper;
    }

    /**
     * 新一轮开始：绑定租户/run 上下文并清空上一轮累积（串行执行，否则同 session 并发会串数据）。
     * 引擎在知识库检索（withSessionMemory）之前调用，随后 {@link #recordKb} 记录检索步骤（seq=1），
     * 检索完成后 {@link #setCounts} 落 review/kb 计数（prompt_info）。
     */
    public void begin(long tenantId, long runId) {
        this.tenantId = tenantId;
        this.runId = runId;
        reviewCount = 0;
        kbHits = 0;
        toolSeq = 0;
        kbRow = null;
        usages.clear();
        toolCalls.clear();
        actingStartNanos.clear();
        actingCalls.clear();
    }

    /** 会话回顾 / 知识库注入计数（withSessionMemory 完成后写入，落 prompt_info）。 */
    public void setCounts(int reviewCount, int kbHits) {
        this.reviewCount = reviewCount;
        this.kbHits = kbHits;
    }

    /**
     * 知识库检索步骤（引擎上下文装配时调用）：作为调用链首步（seq=1）记录并实时落库，
     * 未命中（hits=0）时也保留步骤（ok=false，error=no_hit）——链路完整性；
     * 后续工具调用序号顺延（首个工具 seq=2）。
     */
    public void recordKb(String query, int hits, long durationMs) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "knowledge_search");
        m.put("params", truncate(query, PARAMS_LIMIT));
        m.put("duration_ms", durationMs);
        m.put("ok", hits > 0);
        m.put("error", hits > 0 ? null : "no_hit");
        m.put("seq", 1);
        m.put("target", null);
        kbRow = m;
        toolSeq = 1;
        try {
            toolCallMapper.insert(toEntity(tenantId, runId, m));
        } catch (Exception ex) {
            log.warn("live kb persist failed session={}: {}", sessionId, ex.toString());
        }
    }

    public int reviewCount() {
        return reviewCount;
    }

    public int kbHits() {
        return kbHits;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(ev -> {
            if (ev instanceof ModelCallEndEvent end) {
                ChatUsage u = end.getUsage();
                if (u != null) {
                    usages.add(new UsageAcc(u.getInputTokens(), u.getOutputTokens(),
                            u.getCachedTokens(), (long) u.getTime()));
                    log.debug("model call session={} input={} output={} cached={} durationMs={}",
                            sessionId, u.getInputTokens(), u.getOutputTokens(),
                            u.getCachedTokens(), (long) u.getTime());
                }
            }
        });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        for (ToolUseBlock t : input.toolCalls()) {
            actingCalls.put(t.getId(), t);
        }
        return next.apply(input).doOnNext(ev -> {
            if (ev instanceof ToolResultStartEvent s) {
                actingStartNanos.put(s.getToolCallId(), System.nanoTime());
                ToolUseBlock b = actingCalls.get(s.getToolCallId());
                log.debug("tool start session={} toolCallId={} name={} params={}",
                        sessionId, s.getToolCallId(),
                        b == null ? "?" : b.getName(),
                        truncate(serializeParams(b == null ? null : b.getInput()), 200));
            } else if (ev instanceof ToolResultEndEvent e) {
                ToolUseBlock block = actingCalls.remove(e.getToolCallId());
                Long t0 = actingStartNanos.remove(e.getToolCallId());
                long durationMs = t0 == null ? -1L : (System.nanoTime() - t0) / 1_000_000L;
                boolean ok = e.getState() == ToolResultState.SUCCESS;
                log.debug("tool end session={} toolCallId={} name={} durationMs={} ok={} error={}",
                        sessionId, e.getToolCallId(), block == null ? "?" : block.getName(),
                        durationMs, ok, ok ? "" : truncate(e.getState().name(), 200));
                if (block == null || toolCalls.size() >= TOOL_CALLS_LIMIT) {
                    return;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("name", block.getName());
                m.put("params", truncate(serializeParams(block.getInput()), PARAMS_LIMIT));
                m.put("duration_ms", t0 == null ? null : durationMs);
                m.put("ok", ok);
                m.put("error", ok ? null : e.getState().name());
                // 调用链回放字段：run 内序号 / 工具调用 id / 入参解析出的动作·函数名
                m.put("tool_call_id", e.getToolCallId());
                m.put("seq", ++toolSeq);
                m.put("target", parseTarget(block.getName(), block.getInput()));
                toolCalls.add(m);
                // 运行中实时落库：调用链回放可查执行中的链路；失败仅告警（完成时引擎按 seq 去重兜底补齐）
                try {
                    toolCallMapper.insert(toEntity(tenantId, runId, m));
                } catch (Exception ex) {
                    log.warn("live tool call persist failed session={} toolCallId={}: {}",
                            sessionId, e.getToolCallId(), ex.toString());
                }
            }
        });
    }

    /** Map 明细 → agent_tool_call 行（运行时实时落库与引擎完成时兜底共用，保证同构幂等）。 */
    static AgentToolCallEntity toEntity(long tenantId, long runId, Map<String, Object> tc) {
        AgentToolCallEntity tce = new AgentToolCallEntity();
        tce.setTenantId(tenantId);
        tce.setRunId(runId);
        Object seq = tc.get("seq");
        tce.setSeq(seq == null ? null : ((Number) seq).intValue());
        Object target = tc.get("target");
        tce.setTarget(target == null ? null : String.valueOf(target));
        String toolName = String.valueOf(tc.get("name"));
        tce.setName(toolName);
        if ("knowledge_search".equals(toolName)) {
            // 知识库检索步骤（引擎注入前）：独立 kind，不入工具泳道
            tce.setKind("kb");
        } else if (target != null) {
            tce.setKind("applyAction".equals(toolName) ? "action" : "function");
        } else {
            tce.setKind("tool");
        }
        tce.setArgs(String.valueOf(tc.get("params")));
        Object dms = tc.get("duration_ms");
        if (dms instanceof Number) {
            tce.setDurationMs(((Number) dms).intValue());
        }
        tce.setOk(Boolean.TRUE.equals(tc.get("ok")));
        Object err = tc.get("error");
        if (err != null) {
            tce.setError(String.valueOf(err));
        }
        return tce;
    }

    /** 汇总当前 run 的模型调用 usage，然后清空。 */
    public Map<String, Object> drainUsage() {
        int input = 0, output = 0, cached = 0;
        long modelMs = 0;
        for (UsageAcc u : usages) {
            input += u.inputTokens;
            output += u.outputTokens;
            cached += u.cachedTokens;
            modelMs += u.modelMs;
        }
        int calls = usages.size();
        usages.clear();
        Map<String, Object> m = new HashMap<>();
        m.put("model", modelName);
        m.put("input_tokens", input);
        m.put("output_tokens", output);
        m.put("cached_tokens", cached);
        m.put("model_calls", calls);
        m.put("model_ms", modelMs);
        return m;
    }

    /** 取走当前 run 的调用链明细（知识库检索步骤排首，随后为工具调用）。 */
    public List<Map<String, Object>> drainToolCalls() {
        List<Map<String, Object>> out = new ArrayList<>(toolCalls);
        if (kbRow != null) {
            out.add(0, kbRow);
        }
        kbRow = null;
        toolCalls.clear();
        return out;
    }

    /** 参数序列化：Map 输入转结构化 JSON（此前 String.valueOf 得到 Java map toString，统计解析脆弱）。 */
    private static String serializeParams(Object input) {
        if (input instanceof Map<?, ?>) {
            try {
                return JsonUtils.write(input);
            } catch (Exception ignored) {
                // 序列化失败回退 toString
            }
        }
        return String.valueOf(input);
    }

    /**
     * 从 applyAction / callFunction 调用入参解析动作/函数名（与 AgentStatsService 同语义）：
     * applyAction → args.action，callFunction → args.name；其余工具返回 null。
     */
    private static String parseTarget(String toolName, Object input) {
        if (!"applyAction".equals(toolName) && !"callFunction".equals(toolName)) {
            return null;
        }
        String key = "applyAction".equals(toolName) ? "action" : "name";
        if (input instanceof Map<?, ?>) {
            Object v = ((Map<?, ?>) input).get(key);
            return v == null ? null : String.valueOf(v);
        }
        String s = String.valueOf(input).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtils.readMap(s);
            Object v = map.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        } catch (Exception ignored) {
            // 非 JSON（Java map toString），走下方正则
        }
        int i = s.indexOf(key + "=");
        if (i >= 0) {
            String rest = s.substring(i + key.length() + 1).trim();
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

    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    private record UsageAcc(int inputTokens, int outputTokens, int cachedTokens, long modelMs) {
    }
}