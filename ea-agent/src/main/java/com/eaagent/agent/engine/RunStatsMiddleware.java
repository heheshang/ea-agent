package com.eaagent.agent.engine;

import com.eaagent.common.JsonUtils;
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
    private final List<UsageAcc> usages = new ArrayList<>();
    private final List<Map<String, Object>> toolCalls = new ArrayList<>();
    /** run 内工具调用序号（ToolResultEnd 完成顺序，1 起；供调用链回放）。 */
    private int toolSeq = 0;
    /** 当前 run 的会话回顾注入条数（引擎 begin 时写入，落 prompt_info）。 */
    private int reviewCount = 0;

    // onActing 计时/匹配表：以 toolCallId 关联 Start 与 End
    private final Map<String, Long> actingStartNanos = new HashMap<>();
    private final Map<String, ToolUseBlock> actingCalls = new HashMap<>();

    public RunStatsMiddleware(String modelName, String sessionId) {
        this.modelName = modelName;
        this.sessionId = sessionId;
    }

    /** 新一轮开始：清空上一轮累积（串行执行，否则同 session 并发会串数据）。 */
    public void begin(int reviewCount) {
        this.reviewCount = reviewCount;
        toolSeq = 0;
        usages.clear();
        toolCalls.clear();
        actingStartNanos.clear();
        actingCalls.clear();
    }

    public int reviewCount() {
        return reviewCount;
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
            }
        });
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

    /** 取走当前 run 的工具调用明细。 */
    public List<Map<String, Object>> drainToolCalls() {
        List<Map<String, Object>> out = new ArrayList<>(toolCalls);
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