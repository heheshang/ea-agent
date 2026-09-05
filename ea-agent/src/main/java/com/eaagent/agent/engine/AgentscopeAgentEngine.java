package com.eaagent.agent.engine;

import com.eaagent.agent.event.EngineEvent;
import com.eaagent.agent.mcp.McpClientRegistry;
import com.eaagent.agent.service.KnowledgeBaseService;
import com.eaagent.agent.tool.AgentToolRegistry;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.mapper.AgentToolCallMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import com.eaagent.ontology.model.AgentToolCallEntity;
import com.eaagent.ontology.model.KnowledgeEntity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;

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
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * agentscope 引擎（4.1 主线）：HarnessAgent + 租户工具集。
 * 条件：ea.agentscope.model 配置时生效；按 sessionId 缓存 agent（RuntimeContext 显式会话隔离）。
 * 事件映射（v2 AgentEvent 流）：ThinkingBlockDelta→thinking_delta、TextBlockDelta→text_delta、
 * 工具结果按 toolCallId 聚合→action_result、AgentResult→落库摘要；done 由 AgentService 补齐。
 */
@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${ea.agentscope.model:}')")
public class AgentscopeAgentEngine implements AgentEngine {
    private static final Logger log = LoggerFactory.getLogger(AgentscopeAgentEngine.class);

    private static final String SYS_PROMPT = """
            你是 EA-Agent 智能运营助手。职责：基于客户画像与事件数据给出触达建议，并可通过工具执行查询/动作。
            约束：
            1. 只使用工具返回的数据；触达前必须核对退订、冷却窗与灰度；所有面向用户的回复一律使用中文（含总结、澄清、拒绝、计划），输出简洁、可执行。
            2. 只能调用运营工具（客户/人群/活动/触达/事件查询、applyAction 触达、callFunction 咨询函数）与 MCP 接入的外部工具（名称含 mcp_ 前缀），其余工具一概禁止；禁止探索文件、代码或系统资源。
            3. 用户说「继续/接着做/然后呢」等延续指令时，依据【会话回顾】中的最近目标与结果直接推进，不要重新探索。
            4. 当上下文中出现【知识库】材料时，优先将其作为业务规则与事实的依据；与工具实时查询结果冲突时，以实时查询结果为准。""";

    /** 会话记忆：最多回顾的轮次数。 */
    private static final int MEMORY_ROUNDS = 5;
    /** 知识库注入：单条内容最大注入长度（防 token 膨胀，超出截断）。 */
    private static final int KNOWLEDGE_CONTENT_LIMIT = 500;
    /** 回顾中单条目标截断长度（防 token 膨胀）。 */
    private static final int MEMORY_GOAL_LIMIT = 150;
    /** 回顾中单条结果摘要截断长度。 */
    private static final int MEMORY_SUMMARY_LIMIT = 200;
    /** 落库摘要最大长度（完整回复截断，防 jsonb/token 膨胀）。 */
    private static final int SUMMARY_STORE_LIMIT = 600;
    /** 系统提示词版本（统计维度 prompt_info.sys_prompt_version，改提示词结构时递增）。 */
    private static final String SYS_PROMPT_VERSION = "v7";

    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final String workspace;
    /** 模型单价（美元/token，DeepSeek V4 Flash 参考：输入 $0.14/M、输出 $0.28/M、缓存命中 $0.0028/M，可经配置调整）。 */
    private final double inputPrice;
    private final double outputPrice;
    private final double cachedPrice;
    private final AgentToolRegistry toolRegistry;
    private final McpClientRegistry mcpRegistry;
    private final KnowledgeBaseService knowledgeService;
    /** 知识库每轮注入条数上限（ea.knowledge.top-k，默认 3）。 */
    private final int knowledgeTopK;
    private final AgentRunMapper runMapper;
    private final AgentToolCallMapper toolCallMapper;
    /** 技能目录（技能仓库 Layer-2，配置驱动；默认仓库内 agentscope-skills/）。 */
    private final String skillsDir;
    private final Map<String, HarnessAgent> sessions = new ConcurrentHashMap<>();
    /** 按 session 与 HarnessAgent 一一对应的统计采集器（stream 完成时 drain 落库）。 */
    private final Map<String, RunStatsMiddleware> statses = new ConcurrentHashMap<>();

    public AgentscopeAgentEngine(
            @Value("${ea.agentscope.model}") String model,
            @Value("${ea.agentscope.api-key:}") String apiKey,
            @Value("${ea.agentscope.base-url:}") String baseUrl,
            @Value("${ea.agentscope.workspace-dir:.agentscope/workspace}") String workspace,
            @Value("${ea.agentscope.pricing.input:0.00000014}") double inputPrice,
            @Value("${ea.agentscope.pricing.output:0.00000028}") double outputPrice,
            @Value("${ea.agentscope.pricing.cached:0.0000000028}") double cachedPrice,
            @Value("${ea.agentscope.skills-dir:agentscope-skills}") String skillsDir,
            AgentToolRegistry toolRegistry,
            McpClientRegistry mcpRegistry,
            KnowledgeBaseService knowledgeService,
            @Value("${ea.knowledge.top-k:3}") int knowledgeTopK,
            AgentRunMapper runMapper,
            AgentToolCallMapper toolCallMapper) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.workspace = workspace;
        this.inputPrice = inputPrice;
        this.outputPrice = outputPrice;
        this.cachedPrice = cachedPrice;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.knowledgeService = knowledgeService;
        this.knowledgeTopK = Math.max(knowledgeTopK, 0);
        this.skillsDir = skillsDir;
        this.runMapper = runMapper;
        this.toolCallMapper = toolCallMapper;
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

    /** 上下文打包结果：消息列表 + 注入的回顾条数 + 注入的知识库条数（统计 prompt_info.memory_review_count / kb_hits）。 */
    private record MemoryPack(List<Msg> messages, int reviewCount, int kbHits) {
    }

    /**
     * 上下文装配（Step 1）：会话记忆（同 (tenant, session) 已完成轮次的最近 N 条 goal+status，
     * 拼「会话回顾」用户消息）+ 知识库检索（与 userInput 相关度 topK 条目，拼「知识库」用户消息），
     * 均置于当前 userInput 之前；注入消息而非 sysPrompt——HarnessAgent 按 session 缓存只建一次，
     * sysPrompt 不会随轮次刷新。
     * 无历史/无命中时对应消息不拼——行为退化为现状（零回归）。
     */
    private MemoryPack withSessionMemory(RunContext rc, String userInput, RunStatsMiddleware statsMw) {
        List<AgentRunEntity> history = runMapper.selectList(new QueryWrapper<AgentRunEntity>()
                .select(AgentRunEntity.COL_ID, AgentRunEntity.COL_GOAL, AgentRunEntity.COL_STATUS, AgentRunEntity.COL_SUMMARY)
                .eq(AgentRunEntity.COL_TENANT_ID, rc.tenantId())
                .eq(AgentRunEntity.COL_SESSION_ID, rc.sessionId())
                .ne(AgentRunEntity.COL_STATUS, "NEW")
                .ne(AgentRunEntity.COL_ID, Long.valueOf(rc.runId()))
                .orderByDesc(AgentRunEntity.COL_ID)
                .last("LIMIT " + MEMORY_ROUNDS));
        List<Msg> messages = new ArrayList<>(history.size() + 2);
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
        // 知识库检索注入（RAG）：同一确定性打分（KnowledgeBaseService），无命中不注入（零回归）；
        // 检索本身作为调用链首步（seq=1）经 middleware 实时落库（运行中链路含知识库检索维度）
        int kbHits = 0;
        long kbStart = System.nanoTime();
        List<KnowledgeEntity> kb = knowledgeService.search(rc.tenantId(), userInput);
        long kbMs = (System.nanoTime() - kbStart) / 1_000_000L;
        if (statsMw != null) {
            statsMw.recordKb(userInput, kb.size(), kbMs);
        }
        if (!kb.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "【知识库】以下是系统检索到与本次请求相关度最高的知识库条目（按相关度排序；如无关请忽略，与实时查询结果冲突时以实时查询结果为准）：\n");
            for (KnowledgeEntity e : kb) {
                sb.append("- 《").append(e.getTitle()).append('》');
                List<String> tags = e.getTags();
                if (tags != null && !tags.isEmpty()) {
                    sb.append(" 标签：").append(String.join("、", tags));
                }
                String content = e.getContent() == null ? "" : e.getContent();
                if (content.length() > KNOWLEDGE_CONTENT_LIMIT) {
                    content = content.substring(0, KNOWLEDGE_CONTENT_LIMIT) + "…";
                }
                sb.append("\n  内容：").append(content).append('\n');
            }
            messages.add(new UserMessage(sb.toString()));
            kbHits = kb.size();
        }
        messages.add(new UserMessage(userInput));
        log.debug("session memory runId={} sessionId={} reviewCount={} kbHits={}",
                rc.runId(), rc.sessionId(), history.size(), kbHits);
        return new MemoryPack(messages, history.size(), kbHits);
    }

    @Override
    public Flux<EngineEvent> stream(RunContext rc, String userInput) {
        HarnessAgent a = sessions.computeIfAbsent(rc.sessionId(), sid -> {
            Toolkit tk = new Toolkit();
            for (AgentTool t : toolRegistry.forTenant(rc.tenantId(), rc.userId(), rc.role())) {
                tk.registerAgentTool(t);
            }
            // MCP（ADR-5 落地）：外部工具经 MCP 协议并入工具集，LLM 侧与本地工具无感知差异。
            mcpRegistry.registerInto(tk);
            // Skill（详细设计 4.2）：显式技能目录（Layer-2）并入 harness 技能仓库；workspace
            // skills 目录（Layer-3/4）由 harness 默认装配，无需在此处理。
            List<io.agentscope.core.skill.repository.AgentSkillRepository> skillRepos =
                    skillsDir == null || skillsDir.isBlank() ? List.of()
                            : java.nio.file.Files.isDirectory(Paths.get(skillsDir))
                                    ? List.of(new FileSystemSkillRepository(Paths.get(skillsDir).toAbsolutePath()))
                                    : List.of();
            RunStatsMiddleware statsMw = new RunStatsMiddleware(model, sid, toolCallMapper);
            statses.put(sid, statsMw);
            return HarnessAgent.builder()
                    .name("ea-operator")
                    .description("智能运营助手")
                    .sysPrompt(SYS_PROMPT)
                    .model(resolveModel())
                    .workspace(Paths.get(workspace).toAbsolutePath())
                    .toolkit(tk)
                    .skillRepositories(skillRepos)
                    .defaultSessionId(sid)
                    .middleware(statsMw)
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableMemoryTools()
                    .disableSubagents()
                    .build();
        });
        // MCP 工具以 ToolBase 注册，默认权限语义为「非只读工具每次调用需人工授权」（ASK）；
        // 无人值守运营引擎无 HITL 通道，ASK 会使工具调用悬空（发 RequireUserConfirmEvent +
        // RequestStopEvent，工具实际不执行）。切 BYPASS：全部放行（库级 API，持久化到会话槽，
        // 后续同会话调用保持）。
        a.setPermissionMode(null, rc.sessionId(), io.agentscope.core.permission.PermissionMode.BYPASS);
        RunStatsMiddleware statsMw = statses.get(rc.sessionId());
        AtomicReference<String> finalReply = new AtomicReference<>();
        Map<String, StringBuilder> toolResults = new HashMap<>();
        Map<String, StringBuilder> toolArgs = new HashMap<>();
        if (statsMw != null) {
            // 先绑定租户/run 上下文，知识库检索（withSessionMemory）完成后 recordKb 实时落库首步
            statsMw.begin(rc.tenantId(), Long.valueOf(rc.runId()));
        }
        MemoryPack pack = withSessionMemory(rc, userInput, statsMw);
        if (statsMw != null) {
            statsMw.setCounts(pack.reviewCount(), pack.kbHits());
        }
        log.info("stream start runId={} sessionId={} model={} memoryReview={} kbHits={}",
                rc.runId(), rc.sessionId(), model, pack.reviewCount(), pack.kbHits());
        // v2 流式 API：streamEvents → Flux<AgentEvent>（Delta 原生，无需 StreamOptions 开关）；
        // RuntimeContext 显式携带 sessionId，语义同 v1 defaultSessionId 会话隔离
        return a.streamEvents(pack.messages(),
                        RuntimeContext.builder().sessionId(rc.sessionId()).build())
                .flatMap(ev -> Mono.justOrEmpty(mapAgentEvent(ev, toolResults, toolArgs, finalReply)))
                .doOnComplete(() -> {
                    persistSummary(rc, new StringBuilder(finalReply.get() == null ? "" : finalReply.get()));
                    persistStats(rc, userInput, statsMw);
                    log.info("stream complete runId={} sessionId={} replyLen={} statsPersisted={}",
                            rc.runId(), rc.sessionId(),
                            finalReply.get() == null ? 0 : finalReply.get().length(),
                            statsMw != null);
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
     * cost（按单价估算，缓存命中输入按缓存价）/ prompt_info（系统提示词版本长度 + 回顾注入数 + 知识库注入数）。
     * tokens_used 兼容旧语义 = 输入 + 输出。旧 run 四列 NULL 兼容，统计 API 按 NULL 处理。
     */
    private void persistStats(RunContext rc, String userInput, RunStatsMiddleware statsMw) {
        if (statsMw == null) {
            return;
        }
        Map<String, Object> usage = statsMw.drainUsage();
        java.util.List<Map<String, Object>> toolCalls = statsMw.drainToolCalls();
        // 调用链明细：运行中各工具调用完成时已实时落库（运行中链路可查），此处按已有 seq 去重
        // 兜底补齐（实时写失败/遗漏时恢复完整，幂等；seq/target 由 middleware 已解析）。
        Set<Integer> existing = toolCallMapper.selectList(new QueryWrapper<AgentToolCallEntity>()
                .eq(AgentToolCallEntity.COL_TENANT_ID, rc.tenantId())
                .eq(AgentToolCallEntity.COL_RUN_ID, Long.valueOf(rc.runId())))
                .stream().map(AgentToolCallEntity::getSeq).collect(Collectors.toSet());
        for (Map<String, Object> tc : toolCalls) {
            Object seq = tc.get("seq");
            if (seq != null && existing.contains(((Number) seq).intValue())) {
                continue;
            }
            toolCallMapper.insert(RunStatsMiddleware.toEntity(
                    rc.tenantId(), Long.valueOf(rc.runId()), tc));
        }
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
        promptInfo.put("kb_hits", statsMw.kbHits());
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

    /**
     * v2 AgentEvent → EngineEvent 映射（保持 SSE 契约不变）：
     * <ul>
     *   <li>ThinkingBlockDelta → thinking_delta（思考增量，原 REASONING）</li>
     *   <li>TextBlockDelta → text_delta（文本增量，原默认分支）</li>
     *   <li>ToolResultTextDelta → 按 toolCallId 聚合到 toolResults（并发工具各自独立）</li>
     *   <li>ToolResultEnd → action_result（结果文本 + 工具名，原 TOOL_RESULT）</li>
     *   <li>AgentResult → 缓存最终回复全文（摘要落库，确保完整文本不依赖增量拼接）</li>
     *   <li>其余事件（start/end/模型调用等）不产生 SSE，返回 null 由上游过滤</li>
     * </ul>
     */
    private static EngineEvent mapAgentEvent(AgentEvent ev,
            Map<String, StringBuilder> toolResults, Map<String, StringBuilder> toolArgs,
            AtomicReference<String> finalReply) {
        if (ev instanceof ThinkingBlockDeltaEvent t) {
            return new EngineEvent("thinking_delta", "{\"text\":" + jsonQuote(t.getDelta()) + "}");
        }
        if (ev instanceof TextBlockDeltaEvent t) {
            return new EngineEvent("text_delta", "{\"text\":" + jsonQuote(t.getDelta()) + "}");
        }
        if (ev instanceof ToolCallDeltaEvent t) {
            // 工具入参增量（v2：模型流式输出工具调用 JSON）→ 供 action_result 标注 Ontology 链路
            toolArgs.computeIfAbsent(t.getToolCallId(), k -> new StringBuilder()).append(t.getDelta());
            return null;
        }
        if (ev instanceof ToolResultTextDeltaEvent t) {
            toolResults.computeIfAbsent(t.getToolCallId(), k -> new StringBuilder())
                    .append(t.getDelta());
            return null;
        }
        if (ev instanceof ToolResultEndEvent t) {
            StringBuilder sb = toolResults.remove(t.getToolCallId());
            String result = sb == null ? "" : sb.toString();
            StringBuilder ab = toolArgs.remove(t.getToolCallId());
            String chain = chainTarget(t.getToolCallName(), ab == null ? "" : ab.toString());
            return new EngineEvent("action_result",
                    "{\"tool\":" + jsonQuote(t.getToolCallName())
                            + ",\"result\":" + jsonQuote(result)
                            + (chain == null ? "" : ",\"chain\":" + chain) + "}");
        }
        if (ev instanceof AgentResultEvent r && r.getResult() != null) {
            finalReply.set(renderFinalText(r.getResult()));
            return null;
        }
        return null;
    }

    /** 最终回复纯文本（摘要落库；与 v1 includeSummaryResult 语义一致，不含思考/工具块）。 */
    private static String renderFinalText(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private static String jsonQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /**
     * 从工具入参原文解析 Ontology 链路目标：applyAction → {"action":"…"}，callFunction → {"function":"…"}。
     * 兼容 JSON 对象与 Java map toString 两种形态；解析失败返回 null（不标注链路）。
     */
    private static String chainTarget(String toolName, String rawArgs) {
        String key;
        String outKey;
        if ("applyAction".equals(toolName)) {
            key = "action";
            outKey = "action";
        } else if ("callFunction".equals(toolName)) {
            key = "name";
            outKey = "function";
        } else {
            return null;
        }
        if (rawArgs == null || rawArgs.isBlank()) {
            return null;
        }
        String s = rawArgs.trim();
        // JSON 形态：{"action":"sendTouch", …}
        int i = s.indexOf("\"" + key + "\"");
        if (i >= 0) {
            int j = s.indexOf(':', i);
            if (j >= 0) {
                String rest = s.substring(j + 1).trim();
                if (rest.startsWith("\"")) {
                    int k = rest.indexOf('"', 1);
                    if (k > 1) {
                        return "{\"" + outKey + "\":" + jsonQuote(rest.substring(1, k)) + "}";
                    }
                }
            }
        }
        // Java map toString 形态：{action=sendTouch, args={…}}
        i = s.indexOf(key + "=");
        if (i >= 0) {
            String rest = s.substring(i + key.length() + 1).trim();
            int comma = rest.indexOf(',');
            int brace = rest.indexOf('}');
            int end;
            if (comma > 0 && brace > 0) {
                end = Math.min(comma, brace);
            } else if (comma > 0) {
                end = comma;
            } else if (brace > 0) {
                end = brace;
            } else {
                end = rest.length();
            }
            String v = rest.substring(0, end).trim();
            if (!v.isEmpty()) {
                return "{\"" + outKey + "\":" + jsonQuote(v) + "}";
            }
        }
        return null;
    }

    @PreDestroy
    public void close() {
        sessions.values().forEach(HarnessAgent::close);
    }
}