package com.eaagent.app.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.api.dto.ChatRequest;
import com.eaagent.agent.service.AgentService;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 对话（4.6/7.1）：POST 建 run（返回 run_id），GET SSE 订阅事件流，
 * POST approval 审批决策（简化：审批人角色由 JWT 提供，MFA 全链路后续迭代）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private final AgentService agentService;
    private final AgentRunMapper runMapper;
    private final ThreadPoolTaskExecutor sseExecutor;
    private final StringRedisTemplate redis;

    public AgentChatController(AgentService agentService, AgentRunMapper runMapper,
                               @Qualifier("sseExecutor") ThreadPoolTaskExecutor sseExecutor,
                               StringRedisTemplate redis) {
        this.agentService = agentService;
        this.runMapper = runMapper;
        this.sseExecutor = sseExecutor;
        this.redis = redis;
    }

    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody ChatRequest req,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionIdHeader) {
        if (req.getGoal() == null || req.getGoal().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "goal required");
        }
        String sessionId = sessionIdHeader != null && !sessionIdHeader.isBlank()
                ? sessionIdHeader : UUID.randomUUID().toString();
        // 会话模式持久化（建议模式门控读 ea:agent:mode:{tenant}:{session}；非法/缺省 = auto 直接执行）
        String mode = req.getMode() == null ? "auto"
                : ("suggest".equals(req.getMode()) || "auto".equals(req.getMode())) ? req.getMode() : "auto";
        redis.opsForValue().set("ea:agent:mode:" + TenantContext.requiredTenantId() + ":" + sessionId,
                mode, Duration.ofSeconds(86400));
        AgentRunEntity run = agentService.startRun(TenantContext.requiredTenantId(),
                TenantContext.userId(), TenantContext.role(), req.getGoal(), sessionId);
        return Result.ok(Map.of("run_id", run.getId(), "status", run.getStatus(), "session_id", sessionId, "mode", mode));
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
                    emitter.send(SseEmitter.event().name("error").data("{\"message\":\"" + be.getMessage() + "\"}"));
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

    @PostMapping("/runs/{id}/approval")
    public Result<Map<String, Object>> approval(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return Result.ok(agentService.approve(id, approved,
                TenantContext.userId(), TenantContext.role()));
    }

    @GetMapping("/runs")
    public Result<List<AgentRunEntity>> runs(@RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String session_id) {
        long tenantId = TenantContext.requiredTenantId();
        QueryWrapper<AgentRunEntity> qw = new QueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_TENANT_ID, tenantId);
        if (session_id != null && !session_id.isBlank()) {
            qw.eq(AgentRunEntity.COL_SESSION_ID, session_id);
        }
        qw.orderByDesc(AgentRunEntity.COL_ID)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return Result.ok(runMapper.selectList(qw));
    }
}