package com.eaagent.agent.engine;

import com.eaagent.agent.event.EngineEvent;
import com.eaagent.agent.tool.AgentToolRegistry;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * agentscope 引擎（4.1 主线）：HarnessAgent + 租户工具集。
 * 条件：ea.agentscope.model 配置时生效；按 sessionId 缓存 agent（defaultSessionId 会话隔离）。
 * 事件映射：REASONING→thinking_delta、TOOL_RESULT→action_result、其余→text_delta、结尾补 done。
 */
@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${ea.agentscope.model:}')")
public class AgentscopeAgentEngine implements AgentEngine {
    private static final Logger log = LoggerFactory.getLogger(AgentscopeAgentEngine.class);

    private static final String SYS_PROMPT = """
            你是 EA-Agent 智能运营助手。职责：基于客户画像与事件数据给出触达建议，并可通过工具执行查询/动作。
            约束：
            1. 只使用工具返回的数据；触达前必须核对退订、冷却窗与灰度；所有面向用户的回复一律使用中文（含总结、澄清、拒绝、计划），输出简洁、可执行。
            2. 只能调用运营工具（客户/人群/活动/触达/事件查询、applyAction 触达、callFunction 咨询函数）；禁止探索文件、代码或系统资源。
            3. 用户说「继续/接着做/然后呢」等延续指令时，依据【会话回顾】中的最近目标与结果直接推进，不要重新探索。""";

    /** 会话记忆：最多回顾的轮次数。 */
    private static final int MEMORY_ROUNDS = 5;
    /** 回顾中单条目标截断长度（防 token 膨胀）。 */
    private static final int MEMORY_GOAL_LIMIT = 150;
    /** 回顾中单条结果摘要截断长度。 */
    private static final int MEMORY_SUMMARY_LIMIT = 200;
    /** 落库摘要最大长度（完整回复截断，防 jsonb/token 膨胀）。 */
    private static final int SUMMARY_STORE_LIMIT = 600;
    /** 系统提示词版本（统计维度 prompt_info.sys_prompt_version，改提示词结构时递增）。 */
    private static final String SYS_PROMPT_VERSION = "v5";

    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final String workspace;
    /** 模型单价（美元/token，DeepSeek V4 Flash 参考：输入 $0.14/M、输出 $0.28/M、缓存命中 $0.0028/M，可经配置调整）。 */
    private final double inputPrice;
    private final double outputPrice;
    private final double cachedPrice;
    private final AgentToolRegistry toolRegistry;
    private final AgentRunMapper runMapper;
    private final Map<String, HarnessAgent> sessions = new ConcurrentHashMap<>();
    /** 按 session 与 HarnessAgent 一一对应的统计采集器（stream 完成时 drain 落库）。 */
    private final Map<String, RunStatsMiddleware> statses = new ConcurrentHashMap<>();

    public AgentscopeAgentEngine(
            @Value("${ea.agentscope.model}") String model,
            @Value("${ea.agentscope.api-key:}") String apiKey,
            @Value("${ea.agentscope.base-url:}") String baseUrl,
            @Value("${ea.agentscope.workspace:ea-agent/workspace}") String workspace,
            @Value("${ea.agentscope.pricing.input:0.00000014}") double inputPrice,
            @Value("${ea.agentscope.pricing.output:0.00000028}") double outputPrice,
            @Value("${ea.agentscope.pricing.cached:0.0000000028}") double cachedPrice,
            AgentToolRegistry toolRegistry,
            AgentRunMapper runMapper) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.workspace = workspace;
        this.inputPrice = inputPrice;
        this.outputPrice = outputPrice;
        this.cachedPrice = cachedPrice;
        this.toolRegistry = toolRegistry;
        this.runMapper = runMapper;
    }

    private Model resolveModel() {
        if ((apiKey == null || apiKey.isBlank()) && (baseUrl == null || baseUrl.isBlank())) {
            return ModelRegistry.resolve(model);
        }
        return ModelRegistry.resolve(model, ModelCreationContext.builder()
                .apiKey(apiKey == null || apiKey.isBlank() ? null : apiKey)
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? null : baseUrl)
                .build());
    }

    @Override
    public boolean available() {
        return true;
    }

    /** 会话记忆打包结果：消息列表 + 注入的回顾条数（统计维度 prompt_info.memory_review_count）。 */
    private record MemoryPack(List<Msg> messages, int reviewCount) {
    }

    /**
     * 会话记忆（Step 1）：同 (tenant, session) 已完成轮次（status != NEW）的最近 N 条
     * goal+status，拼成一段「会话回顾」用户消息置于当前消息之前。
     * 无历史返回空列表——行为退化为现状（零回归）。
     * 注入消息而非 sysPrompt：HarnessAgent 按 session 缓存只建一次，sysPrompt 不会随轮次刷新。
     */
    private MemoryPack withSessionMemory(RunContext rc, String userInput) {
        List<AgentRunEntity> history = runMapper.selectList(new QueryWrapper<AgentRunEntity>()
                .select(AgentRunEntity.COL_ID, AgentRunEntity.COL_GOAL, AgentRunEntity.COL_STATUS, AgentRunEntity.COL_SUMMARY)
                .eq(AgentRunEntity.COL_TENANT_ID, rc.tenantId())
                .eq(AgentRunEntity.COL_SESSION_ID, rc.sessionId())
                .ne(AgentRunEntity.COL_STATUS, "NEW")
                .ne(AgentRunEntity.COL_ID, Long.valueOf(rc.runId()))
                .orderByDesc(AgentRunEntity.COL_ID)
                .last("LIMIT " + MEMORY_ROUNDS));
        List<Msg> messages = new ArrayList<>(history.size() + 1);
        if (!history.isEmpty()) {
            Collections.reverse(history); // 最近在后，时间正序
            StringBuilder sb = new StringBuilder("【会话回顾】以下是你在本会话中此前的交互记录（目标、结果摘要与状态，时间正序）：\n");
            for (AgentRunEntity r : history) {
                String goal = r.getGoal() == null ? "" : r.getGoal();
                if (goal.length() > MEMORY_GOAL_LIMIT) {
                    goal = goal.substring(0, MEMORY_GOAL_LIMIT) + "…";
                }
                sb.append("- [").append(r.getStatus()).append("] 目标：").append(goal);
                String summary = r.getSummary();
                if (summary != null && !summary.isBlank()) {
                    if (summary.length() > MEMORY_SUMMARY_LIMIT) {
                        summary = summary.substring(0, MEMORY_SUMMARY_LIMIT) + "…";
                    }
                    sb.append("\n  结果：").append(summary);
                }
                sb.append('\n');
            }
            sb.append("当前请求可能是这些历史的延续、追问或修正，也可能无关；无关时请忽略回顾，只处理当前请求。");
            messages.add(new UserMessage(sb.toString()));
        }
        messages.add(new UserMessage(userInput));
        log.debug("session memory runId={} sessionId={} reviewCount={}",
                rc.runId(), rc.sessionId(), history.size());
        return new MemoryPack(messages, history.size());
    }

    @Override
    public Flux<EngineEvent> stream(RunContext rc, String userInput) {
        HarnessAgent a = sessions.computeIfAbsent(rc.sessionId(), sid -> {
            Toolkit tk = new Toolkit();
            for (AgentTool t : toolRegistry.forTenant(rc.tenantId(), rc.userId(), rc.role())) {
                tk.registerAgentTool(t);
            }
            RunStatsMiddleware statsMw = new RunStatsMiddleware(model, sid);
            statses.put(sid, statsMw);
            return HarnessAgent.builder()
                    .name("ea-operator")
                    .description("智能运营助手")
                    .sysPrompt(SYS_PROMPT)
                    .model(resolveModel())
                    .workspace(Paths.get(workspace).toAbsolutePath())
                    .toolkit(tk)
                    .defaultSessionId(sid)
                    .middleware(statsMw)
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableMemoryTools()
                    .disableSubagents()
                    .build();
        });
        RunStatsMiddleware statsMw = statses.get(rc.sessionId());
        AtomicReference<StringBuilder> reply = new AtomicReference<>(new StringBuilder());
        MemoryPack pack = withSessionMemory(rc, userInput);
        if (statsMw != null) {
            statsMw.begin(pack.reviewCount());
        }
        log.info("stream start runId={} sessionId={} model={} memoryReview={}",
                rc.runId(), rc.sessionId(), model, pack.reviewCount());
        return a.stream(pack.messages(),
                        StreamOptions.builder()
                                .incremental(true)
                                .includeReasoningChunk(true)
                                .includeSummaryResult(true)
                                .build())
                .map(ev -> {
                    EngineEvent e = mapEvent(ev);
                    // 累积模型最终回复：EngineEvent text_delta（SSE 实证最终回复为 1 个全文事件；
                    // thinking→thinking_delta、工具→action_result 不参与，error 亦不参与）
                    if ("text_delta".equals(e.type())) {
                        String t = renderText(ev.getMessage());
                        if (t != null && !t.isBlank()) {
                            reply.get().append(t);
                        }
                    }
                    return e;
                })
                .doOnComplete(() -> {
                    persistSummary(rc, reply.get());
                    persistStats(rc, userInput, statsMw);
                    log.info("stream complete runId={} sessionId={} replyLen={} statsPersisted={}",
                            rc.runId(), rc.sessionId(), reply.get().length(), statsMw != null);
                })
                .onErrorResume(e -> {
                    log.warn("agentscope stream failed runId={}: {}", rc.runId(), e.toString());
                    return Flux.just(new EngineEvent("error", "{\"error\":" + jsonQuote(e.toString()) + "}"));
                });
    }

    /** 完成时截断最终回复落库 summary（会话记忆注入材料；空回复不写）。 */
    private void persistSummary(RunContext rc, StringBuilder reply) {
        if (reply == null || reply.isEmpty()) {
            log.warn("persistSummary skipped runId={} reason=empty reply", rc.runId());
            return;
        }
        String s = reply.toString().trim();
        if (s.length() > SUMMARY_STORE_LIMIT) {
            s = s.substring(0, SUMMARY_STORE_LIMIT) + "…";
        }
        runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_ID, Long.valueOf(rc.runId()))
                .set(AgentRunEntity.COL_SUMMARY, s));
    }

    /**
     * 完成时回写多维度统计：usage（token 构成/模型调用数）/ tool_calls（工具与 skill 明细）/
     * cost（按单价估算，缓存命中输入按缓存价）/ prompt_info（系统提示词版本长度 + 回顾注入数）。
     * tokens_used 兼容旧语义 = 输入 + 输出。旧 run 四列 NULL 兼容，统计 API 按 NULL 处理。
     */
    private void persistStats(RunContext rc, String userInput, RunStatsMiddleware statsMw) {
        if (statsMw == null) {
            return;
        }
        Map<String, Object> usage = statsMw.drainUsage();
        java.util.List<Map<String, Object>> toolCalls = statsMw.drainToolCalls();
        Number input = (Number) usage.get("input_tokens");
        Number output = (Number) usage.get("output_tokens");
        Number cached = (Number) usage.get("cached_tokens");
        int in = input == null ? 0 : input.intValue();
        int out = output == null ? 0 : output.intValue();
        int cache = cached == null ? 0 : cached.intValue();
        // 缓存命中部分按缓存价计，未命中输入按标准输入价计（业界惯例）
        java.math.BigDecimal cost = java.math.BigDecimal.valueOf(
                inputPrice * (in - cache) + cachedPrice * cache + outputPrice * out);
        Map<String, Object> promptInfo = new java.util.HashMap<>();
        promptInfo.put("sys_prompt_version", SYS_PROMPT_VERSION);
        promptInfo.put("sys_prompt_len", SYS_PROMPT.length());
        promptInfo.put("memory_review_count", statsMw.reviewCount());
        promptInfo.put("input_len", userInput == null ? 0 : userInput.length());

        UpdateWrapper<AgentRunEntity> uw = new UpdateWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_ID, Long.valueOf(rc.runId()))
                .set(AgentRunEntity.COL_TOKENS_USED, (long) (in + out))
                .set(AgentRunEntity.COL_COST, cost);
        if (in > 0 || out > 0) {
            uw.set(AgentRunEntity.COL_USAGE, usage,
                    "typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler");
        }
        if (!toolCalls.isEmpty()) {
            uw.set(AgentRunEntity.COL_TOOL_CALLS, toolCalls,
                    "typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler");
        }
        uw.set(AgentRunEntity.COL_PROMPT_INFO, promptInfo,
                "typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler");
        runMapper.update(null, uw);
    }

    private static EngineEvent mapEvent(Event e) {
        EventType t = e.getType();
        String text = renderText(e.getMessage());
        return switch (t) {
            case REASONING -> new EngineEvent("thinking_delta", "{\"text\":" + jsonQuote(text) + "}");
            case TOOL_RESULT -> new EngineEvent("action_result", "{\"result\":" + jsonQuote(text) + "}");
            default -> new EngineEvent("text_delta", "{\"text\":" + jsonQuote(text) + "}");
        };
    }

    private static String renderText(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb) {
                sb.append(tb.getText());
            } else if (b instanceof ThinkingBlock tk) {
                sb.append(tk.getThinking());
            } else if (b instanceof ToolResultBlock tr) {
                for (ContentBlock ob : tr.getOutput()) {
                    if (ob instanceof TextBlock otb) {
                        sb.append(otb.getText());
                    } else if (ob instanceof ThinkingBlock otk) {
                        sb.append(otk.getThinking());
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String jsonQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @PreDestroy
    public void close() {
        sessions.values().forEach(HarnessAgent::close);
    }
}