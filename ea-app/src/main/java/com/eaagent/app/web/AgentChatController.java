package com.eaagent.app.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.api.dto.ChatCreateRequest;
import com.eaagent.api.dto.ChatRequest;
import com.eaagent.agent.service.AgentService;
import com.eaagent.app.service.AgentChatService;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.common.Texts;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentChatEntity;
import com.eaagent.ontology.model.AgentRunEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话（4.6/7.1）：POST 建 run（返回 run_id），GET SSE 订阅事件流。
 * 会话 HITL 的建议模式门控在 AgentToolRegistry（applyAction 挂起 → approveAction 聊天内放行）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    /** 聊天描述来源（goal 截断）的字符上限。 */
    private static final int CHAT_DESC_LIMIT = 64;

    private final AgentService agentService;
    private final AgentRunMapper runMapper;
    private final AgentChatService chatService;
    private final ThreadPoolTaskExecutor sseExecutor;
    private final StringRedisTemplate redis;

    public AgentChatController(AgentService agentService, AgentRunMapper runMapper,
                               AgentChatService chatService,
                               @Qualifier("sseExecutor") ThreadPoolTaskExecutor sseExecutor,
                               StringRedisTemplate redis) {
        this.agentService = agentService;
        this.runMapper = runMapper;
        this.chatService = chatService;
        this.sseExecutor = sseExecutor;
        this.redis = redis;
    }

    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody ChatRequest req) {
        if (req.getGoal() == null || req.getGoal().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "goal required");
        }
        long tenantId = TenantContext.requiredTenantId();
        // 聊天归属解析（V17）：带 chat_id → 校验归属；缺省 → 按 goal 自动建聊天（描述 = goal 截断），
        // 保证每次对话都有可追踪的聊天 id；默认描述聊天首条 goal 到来时自动更新描述。
        AgentChatEntity chat = req.getChatId() != null
                ? chatService.getChat(tenantId, TenantContext.userId(), req.getChatId())
                : chatService.createChat(tenantId, TenantContext.userId(), Texts.truncate(req.getGoal().trim(), CHAT_DESC_LIMIT));
        chat = chatService.describeFromGoal(tenantId, TenantContext.userId(), chat.getId(), req.getGoal());
        AgentRunEntity latest = runMapper.selectOne(new QueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_TENANT_ID, tenantId)
                .eq(AgentRunEntity.COL_USER_ID, TenantContext.userId())
                .eq(AgentRunEntity.COL_CHAT_ID, chat.getId())
                .orderByDesc(AgentRunEntity.COL_ID).last("LIMIT 1"));
        String sessionId = latest == null ? "chat-" + chat.getId() : latest.getSessionId();
        // 会话模式按租户/用户/会话持久化；仅接受的新 run 可更改模式。
        String mode = req.getMode() == null ? "auto"
                : ("suggest".equals(req.getMode()) || "auto".equals(req.getMode())) ? req.getMode() : "auto";
        AgentRunEntity run = agentService.startRun(tenantId,
                TenantContext.userId(), TenantContext.role(), req.getGoal(), sessionId, chat.getId());
        redis.opsForValue().set("ea:agent:mode:" + tenantId + ":" + TenantContext.userId() + ":" + sessionId,
                mode, Duration.ofSeconds(86400));
        return Result.ok(Map.of(
                "run_id", run.getId(),
                "status", run.getStatus(),
                "session_id", sessionId,
                "mode", mode,
                "chat_id", chat.getId(),
                "description", chat.getDescription()));
    }

    /** 新建聊天（V17）：产出唯一 id + 描述；描述缺省「新对话」，首条 goal 到来时自动更新。 */
    @PostMapping("/chats")
    public Result<Map<String, Object>> createChat(@RequestBody(required = false) ChatCreateRequest req) {
        long tenantId = TenantContext.requiredTenantId();
        AgentChatEntity chat = chatService.createChat(tenantId, TenantContext.userId(),
                req == null ? null : req.getDescription());
        return Result.ok(Map.of("chat_id", chat.getId(), "description", chat.getDescription()));
    }

    /** 当前用户聊天列表（新到旧）。 */
    @GetMapping("/chats")
    public Result<List<AgentChatEntity>> chats(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(chatService.listChats(TenantContext.requiredTenantId(),
                TenantContext.userId(), limit));
    }

    @GetMapping("/chat")
    public SseEmitter subscribe(@RequestParam("request_id") Long runId) {
        AgentRunEntity run = agentService.getRun(runId);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        // 关键：resume 含同步 execute（engine.run().blockLast()，可能数十秒），
        // 必须放到 sseExecutor 线程执行；subscribe 立即返回 emitter，
        // MVC initialize 才能先发 SSE 响应头，否则 emitter.complete() 抢先导致
        // 响应永不开始（前端 EventSource 永远 CONNECTING）。
        sseExecutor.execute(() -> {
            try {
                TenantContext.setIdentity(run.getTenantId(), run.getUserId(), run.getRole());
                agentService.resume(run, emitter);
            } catch (BizException be) {
                // 状态不允许等：错误以 SSE error 事件收尾，避免二次包装
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", be.getMessage())));
                } catch (Exception ignored) {
                    // emitter 已断：忽略
                }
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("{\"message\":\"internal error\"}"));
                } catch (Exception ignored) {
                    // emitter 已断：忽略
                }
            } finally {
                TenantContext.clear();
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/runs")
    public Result<List<AgentRunEntity>> runs(@RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String session_id,
                                             @RequestParam(required = false) Long chat_id) {
        long tenantId = TenantContext.requiredTenantId();
        QueryWrapper<AgentRunEntity> qw = new QueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_TENANT_ID, tenantId);
        qw.eq(AgentRunEntity.COL_USER_ID, TenantContext.userId());
        if (session_id != null && !session_id.isBlank()) {
            qw.eq(AgentRunEntity.COL_SESSION_ID, session_id);
        }
        if (chat_id != null) {
            qw.eq(AgentRunEntity.COL_CHAT_ID, chat_id);
        }
        qw.orderByDesc(AgentRunEntity.COL_ID)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return Result.ok(runMapper.selectList(qw));
    }
}