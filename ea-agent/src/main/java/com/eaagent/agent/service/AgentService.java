package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.agent.engine.AgentEngine;
import com.eaagent.agent.engine.RunContext;
import com.eaagent.agent.event.EngineEvent;
import com.eaagent.api.sse.AgentSseEvent;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.common.Texts;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 会话编排（详细设计 4.3/4.6）：run 生命周期状态机 + Redis 步骤回放 + SSE 推送。
 * 简化（边界）：状态机为顺序执行，断点重连只做步骤回放，执行中并发请求拒绝（E-15002）。
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRunMapper runMapper;
    private final StringRedisTemplate redis;
    private final List<AgentEngine> engines;

    public AgentService(AgentRunMapper runMapper, StringRedisTemplate redis, List<AgentEngine> engines) {
        this.runMapper = runMapper;
        this.redis = redis;
        this.engines = engines;
    }

    private String stepsKey(Long runId) {
        return "ea:run:" + runId + ":steps";
    }

    /**
     * 同会话在途 run（防重用）：NEW/PLANNING/EXECUTING/OBSERVING 视为执行链上未终结，
     * 会话仍被占用；会话 HITL 挂起不落 run 状态（挂起时 run 已 COMPLETED，Redis 挂起项跨轮存续），
     * 用户确认/拒绝后的下一条消息可正常开新 run。
     */
    AgentRunEntity findInflightRun(Long tenantId, Long userId, String sessionId) {
        return runMapper.selectOne(new QueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_TENANT_ID, tenantId)
                .eq(AgentRunEntity.COL_USER_ID, userId)
                .eq(AgentRunEntity.COL_SESSION_ID, sessionId)
                .in(AgentRunEntity.COL_STATUS,
                        AgentRunEntity.STATUS_NEW, AgentRunEntity.STATUS_PLANNING, AgentRunEntity.STATUS_EXECUTING, AgentRunEntity.STATUS_OBSERVING)
                .orderByDesc(AgentRunEntity.COL_ID)
                .last("LIMIT 1"));
    }

    private AgentEngine engine() {
        return engines.stream().filter(AgentEngine::available).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.LLM_CALL_FAILED));
    }

    /** 建 run（POST /api/agent/chat）：NEW 落库，清扫历史步骤缓存。 */
    public AgentRunEntity startRun(Long tenantId, Long userId, String role, String goal, String sessionId, Long chatId) {
        // 同会话防重（修复：挂起 run 占住会话时用户重复提交会生成重复 run——实证 116/117 同 goal 双 run；
        // 拒绝让用户重发而非静默产生第二个在途 run；会话 HITL 挂起时 run 已完结不占用执行，用户确认/拒绝可正常开新 run）
        AgentRunEntity inflight = findInflightRun(tenantId, userId, sessionId);
        if (inflight != null) {
            log.warn("start rejected tenantId={} sessionId={} reason=E-15002 inflight runId={} status={}",
                    tenantId, sessionId, inflight.getId(), inflight.getStatus());
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
        }
        AgentRunEntity run = new AgentRunEntity();
        run.setTenantId(tenantId);
        run.setSessionId(sessionId);
        run.setChatId(chatId);
        run.setUserId(userId);
        run.setRole(role);
        run.setGoal(goal);
        run.setStatus(AgentRunEntity.STATUS_NEW);
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // V18 的部分唯一索引兜住两个请求同时通过在途查询的竞争。
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
        }
        redis.delete(stepsKey(run.getId()));
        log.info("run created runId={} tenantId={} sessionId={} goal={}",
                run.getId(), tenantId, sessionId, Texts.truncate(goal, 100));
        return run;
    }

    public AgentRunEntity getRun(Long id) {
        long tenantId = TenantContext.requiredTenantId();
        Long userId = TenantContext.userId();
        if (userId == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        AgentRunEntity run = runMapper.selectById(id);
        if (run == null || !Objects.equals(run.getTenantId(), tenantId)
                || !Objects.equals(run.getUserId(), userId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        return run;
    }

    /**
     * GET SSE 挂载（4.6）：已有步骤回放；NEW → 执行状态机；执行中 → E-15002。
     */
    public void resume(AgentRunEntity run, SseEmitter emitter) {
        String status = run.getStatus();
        long runId = run.getId();
        List<String> steps = redis.opsForList().range(stepsKey(runId), 0, -1);
        boolean cached = steps != null && !steps.isEmpty();
        log.info("resume mount runId={} tenantId={} status={} redisSteps={}",
                runId, run.getTenantId(), status, cached ? steps.size() : 0);
        if (cached) {
            replay(steps, emitter);
        }
        if (AgentRunEntity.STATUS_COMPLETED.equals(status) || AgentRunEntity.STATUS_FAILED.equals(status)
                || AgentRunEntity.STATUS_CANCELLED.equals(status)) {
            if (!cached && run.getSummary() != null && !run.getSummary().isBlank()) {
                push(emitter, AgentSseEvent.of("text_delta", String.valueOf(runId), Map.of("text", run.getSummary())));
            }
            push(emitter, AgentSseEvent.of("done", String.valueOf(runId), Map.of("status", status)));
            return;
        }
        if (!AgentRunEntity.STATUS_NEW.equals(status)) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
        }
        Instant claimedAt = Instant.now();
        int claimed = runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_ID, runId)
                .eq(AgentRunEntity.COL_TENANT_ID, run.getTenantId())
                .eq(AgentRunEntity.COL_USER_ID, run.getUserId())
                .eq(AgentRunEntity.COL_STATUS, AgentRunEntity.STATUS_NEW)
                .set(AgentRunEntity.COL_STATUS, AgentRunEntity.STATUS_PLANNING)
                .set(AgentRunEntity.COL_UPDATED_AT, claimedAt));
        if (claimed != 1) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
        }
        run.setStatus(AgentRunEntity.STATUS_PLANNING);
        run.setUpdatedAt(claimedAt);
        execute(run, emitter);
    }

    private void execute(AgentRunEntity run, SseEmitter emitter) {
        long runId = run.getId();
        long startNanos = System.nanoTime();
        log.info("run execute start runId={} goal={}", runId, Texts.truncate(run.getGoal(), 100));
        try {
            RunContext rc = new RunContext(run.getTenantId(), run.getUserId(), run.getRole(), run.getSessionId(),
                    String.valueOf(runId), run.getChatId());
            AgentEngine engine = engine();
            List<Map<String, Object>> plan = new ArrayList<>();
            engine.stream(rc, run.getGoal())
                    .doOnNext(ev -> handle(ev, run, emitter, plan))
                    .blockLast();

            String status = AgentRunEntity.STATUS_FAILED.equals(run.getStatus())
                    ? AgentRunEntity.STATUS_FAILED : AgentRunEntity.STATUS_COMPLETED;
            // 引擎完成摘要/统计回写后才释放会话；只更新生命周期字段，绝不回写旧实体快照。
            UpdateWrapper<AgentRunEntity> update = executingRun(run)
                    .set(AgentRunEntity.COL_STATUS, status)
                    .set(AgentRunEntity.COL_UPDATED_AT, Instant.now());
            if (!plan.isEmpty()) {
                update.set(AgentRunEntity.COL_PLAN, plan,
                        "typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler");
            }
            if (runMapper.update(null, update) != 1) {
                throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
            }
            run.setStatus(status);
            push(emitter, AgentSseEvent.of("done", String.valueOf(runId), Map.of("status", status)));
            log.info("run execute complete runId={} status={} durationMs={}",
                    runId, status, (System.nanoTime() - startNanos) / 1_000_000L);
        } catch (Exception e) {
            log.error("agent run {} failed", runId, e);
            int changed = runMapper.update(null, executingRun(run)
                    .set(AgentRunEntity.COL_STATUS, AgentRunEntity.STATUS_FAILED)
                    .set(AgentRunEntity.COL_UPDATED_AT, Instant.now()));
            String status = AgentRunEntity.STATUS_FAILED;
            if (changed == 0) {
                AgentRunEntity current = runMapper.selectById(runId);
                if (current != null) {
                    status = current.getStatus();
                }
            }
            run.setStatus(status);
            push(emitter, AgentSseEvent.of("error", String.valueOf(runId), Map.of("message", String.valueOf(e.getMessage()))));
            push(emitter, AgentSseEvent.of("done", String.valueOf(runId), Map.of("status", status)));
        }
    }

    /** 终态不可被延迟到达的引擎事件或 watchdog 扫描后的旧快照复活。 */
    private UpdateWrapper<AgentRunEntity> executingRun(AgentRunEntity run) {
        return new UpdateWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_ID, run.getId())
                .eq(AgentRunEntity.COL_TENANT_ID, run.getTenantId())
                .eq(AgentRunEntity.COL_USER_ID, run.getUserId())
                .in(AgentRunEntity.COL_STATUS, AgentRunEntity.STATUS_PLANNING,
                        AgentRunEntity.STATUS_EXECUTING, AgentRunEntity.STATUS_OBSERVING);
    }

    private void handle(EngineEvent ev, AgentRunEntity run, SseEmitter emitter, List<Map<String, Object>> plan) {
        // done 由服务在引擎清理/统计全部结束后统一发送；error 后不再接受后续内容。
        if ("done".equals(ev.type()) || AgentRunEntity.STATUS_FAILED.equals(run.getStatus())) {
            return;
        }
        Map<String, Object> data = JsonUtils.readMap(ev.data());
        long runId = run.getId();
        log.debug("handle event runId={} type={} data={}", runId, ev.type(), Texts.truncate(ev.data(), 200));
        switch (ev.type()) {
            case "plan" -> {
                run.setStatus(AgentRunEntity.STATUS_PLANNING);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
                if (steps != null) {
                    plan.addAll(steps);
                }
            }
            case "tool_call" -> run.setStatus(AgentRunEntity.STATUS_EXECUTING);
            case "action_result" -> run.setStatus(AgentRunEntity.STATUS_OBSERVING);
            case "thinking_delta", "text_delta" -> run.setStatus(AgentRunEntity.STATUS_EXECUTING);
            case "error" -> run.setStatus(AgentRunEntity.STATUS_FAILED);
            default -> { }
        }
        run.setUpdatedAt(Instant.now());
        UpdateWrapper<AgentRunEntity> update = executingRun(run)
                .set(AgentRunEntity.COL_UPDATED_AT, run.getUpdatedAt());
        if (!AgentRunEntity.STATUS_FAILED.equals(run.getStatus())) {
            update.set(AgentRunEntity.COL_STATUS, run.getStatus());
        }
        if (runMapper.update(null, update) != 1) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
        }
        AgentSseEvent sse = AgentSseEvent.of(ev.type(), String.valueOf(runId), data);
        appendStep(runId, sse);
        push(emitter, sse);
    }

    private void replay(List<String> steps, SseEmitter emitter) {
        for (String frame : steps) {
            Map<String, Object> m = JsonUtils.readMap(frame);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = m.get("data") instanceof Map
                    ? (Map<String, Object>) m.get("data")
                    : Map.of();
            AgentSseEvent e = AgentSseEvent.of(
                    String.valueOf(m.get("event")),
                    String.valueOf(m.get("run_id")),
                    data);
            push(emitter, e);
        }
    }

    private void appendStep(Long runId, AgentSseEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", e.getEvent());
        m.put("run_id", runId);
        m.put("data", e.getData());
        redis.opsForList().rightPush(stepsKey(runId), JsonUtils.write(m));
    }

    private void push(SseEmitter emitter, AgentSseEvent e) {
        try {
            emitter.send(SseEmitter.event().name(e.getEvent()).data(JsonUtils.write(e.getData())));
        } catch (Exception ex) {
            log.warn("sse push failed: {}", ex.toString());
        }
    }
}