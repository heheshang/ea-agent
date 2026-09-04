package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.agent.engine.AgentEngine;
import com.eaagent.agent.engine.RunContext;
import com.eaagent.agent.event.EngineEvent;
import com.eaagent.api.sse.AgentSseEvent;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话编排（详细设计 4.3/4.6）：run 生命周期状态机 + Redis 步骤回放 + SSE 推送。
 * 简化（边界）：状态机为顺序执行，断点重连只做步骤回放，执行中并发请求拒绝（E-15002）。
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    public static final String ST_NEW = "NEW";
    public static final String ST_PLANNING = "PLANNING";
    public static final String ST_AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String ST_EXECUTING = "EXECUTING";
    public static final String ST_OBSERVING = "OBSERVING";
    public static final String ST_COMPLETED = "COMPLETED";
    public static final String ST_FAILED = "FAILED";
    public static final String ST_CANCELLED = "CANCELLED";

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

    private AgentEngine engine() {
        return engines.stream().filter(AgentEngine::available).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.LLM_CALL_FAILED));
    }

    /** 建 run（POST /api/agent/chat）：NEW 落库，清扫历史步骤缓存。 */
    public AgentRunEntity startRun(Long tenantId, Long userId, String role, String goal, String sessionId) {
        AgentRunEntity run = new AgentRunEntity();
        run.setTenantId(tenantId);
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setGoal(goal);
        run.setStatus(ST_NEW);
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        runMapper.insert(run);
        redis.delete(stepsKey(run.getId()));
        log.info("run created runId={} tenantId={} sessionId={} goal={}",
                run.getId(), tenantId, sessionId, truncate(goal, 100));
        return run;
    }

    public AgentRunEntity getRun(Long id) {
        AgentRunEntity run = runMapper.selectById(id);
        if (run == null) {
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
        int stepCount = steps == null ? 0 : steps.size();
        log.info("resume mount runId={} tenantId={} status={} redisSteps={}",
                runId, run.getTenantId(), status, stepCount);
        if (steps != null && !steps.isEmpty()) {
            log.info("resume runId={} mode=replay steps={}", runId, stepCount);
            replay(steps, emitter);
            if (ST_EXECUTING.equals(status) || ST_PLANNING.equals(status) || ST_OBSERVING.equals(status)) {
                // 另一线程执行中：拒绝并发（简化）；状态未落盘完成态时如实回报
                log.warn("resume rejected runId={} status={} reason=E-15002 concurrent execution", runId, status);
                throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
            }
            if (ST_COMPLETED.equals(status) || ST_FAILED.equals(status) || ST_CANCELLED.equals(status)) {
                push(emitter, AgentSseEvent.of("done", String.valueOf(runId),
                        Map.of("status", status)));
                return;
            }
        } else {
            log.info("resume runId={} status={} mode=new execution", runId, status);
        }
        if (!ST_NEW.equals(status)) {
            log.warn("resume rejected runId={} status={} reason=E-15002 state not allowed", runId, status);
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
        }
        execute(run, emitter);
    }

    /** 审批（4.4）：AWAITING_APPROVAL → EXECUTING / CANCELLED，记录 decision。 */
    public Map<String, Object> approve(Long runId, boolean approved, Long reviewerId, String reviewerRole) {
        AgentRunEntity run = getRun(runId);
        if (!ST_AWAITING_APPROVAL.equals(run.getStatus())) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
        }
        List<Map<String, Object>> decisions = run.getDecisions() == null ? new ArrayList<>() : new ArrayList<>(run.getDecisions());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "approval");
        d.put("approved", approved);
        d.put("reviewer_id", reviewerId);
        d.put("reviewer_role", reviewerRole);
        d.put("at", Instant.now().toString());
        decisions.add(d);
        String newStatus = approved ? ST_EXECUTING : ST_CANCELLED;
        log.info("approve runId={} approved={} reviewerId={} targetStatus={}",
                runId, approved, reviewerId, newStatus);
        // 靶向更新：只改状态/审批记录/更新时间，避免 updateById 用旧快照覆写统计列（usage/tokens_used/cost）
        runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                .eq(AgentRunEntity.COL_ID, runId)
                .set(AgentRunEntity.COL_STATUS, newStatus)
                .set(AgentRunEntity.COL_DECISIONS, decisions,
                        "typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler")
                .set(AgentRunEntity.COL_UPDATED_AT, Instant.now()));
        return Map.of("run_id", runId, "status", newStatus);
    }

    // ---------- 内部 ----------

    private void execute(AgentRunEntity run, SseEmitter emitter) {
        long runId = run.getId();
        long startNanos = System.nanoTime();
        log.info("run execute start runId={} goal={}", runId, truncate(run.getGoal(), 100));
        try {
            run.setStatus(ST_PLANNING);
            run.setUpdatedAt(Instant.now());
            runMapper.updateById(run);

            RunContext rc = new RunContext(run.getTenantId(), run.getUserId(), "USER", run.getSessionId(), String.valueOf(runId));
            AgentEngine engine = engine();
            List<Map<String, Object>> plan = new ArrayList<>();
            engine.stream(rc, run.getGoal())
                    .doOnNext(ev -> handle(ev, run, emitter, plan))
                    .blockLast();

            if (!ST_FAILED.equals(run.getStatus())) {
                // 靶向更新：只改状态/plan/更新时间。tokens_used/usage/cost/prompt_info 由引擎统计回写，
                // updateById 会用旧快照把它们覆写回零，故禁用
                UpdateWrapper<AgentRunEntity> uw = new UpdateWrapper<AgentRunEntity>()
                        .eq(AgentRunEntity.COL_ID, runId)
                        .set(AgentRunEntity.COL_STATUS, ST_COMPLETED)
                        .set(AgentRunEntity.COL_UPDATED_AT, Instant.now());
                if (plan != null && !plan.isEmpty()) {
                    uw.set(AgentRunEntity.COL_PLAN, plan,
                            "typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler");
                }
                runMapper.update(null, uw);
                push(emitter, AgentSseEvent.of("done", String.valueOf(runId), Map.of("status", ST_COMPLETED)));
                log.info("run execute complete runId={} status={} durationMs={}",
                        runId, ST_COMPLETED, (System.nanoTime() - startNanos) / 1_000_000L);
            }
        } catch (Exception e) {
            log.error("agent run {} failed", runId, e);
            run.setStatus(ST_FAILED);
            run.setUpdatedAt(Instant.now());
            runMapper.updateById(run);
            push(emitter, AgentSseEvent.of("error", String.valueOf(runId), Map.of("message", String.valueOf(e.getMessage()))));
            push(emitter, AgentSseEvent.of("done", String.valueOf(runId), Map.of("status", ST_FAILED)));
        }
    }

    private void handle(EngineEvent ev, AgentRunEntity run, SseEmitter emitter, List<Map<String, Object>> plan) {
        Map<String, Object> data = JsonUtils.readMap(ev.data());
        long runId = run.getId();
        log.debug("handle event runId={} type={} data={}", runId, ev.type(), truncate(ev.data(), 200));
        switch (ev.type()) {
            case "plan" -> {
                run.setStatus(ST_PLANNING);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
                if (steps != null) {
                    plan.addAll(steps);
                }
            }
            case "tool_call" -> run.setStatus(ST_EXECUTING);
            case "action_result" -> run.setStatus(ST_OBSERVING);
            case "thinking_delta", "text_delta" -> run.setStatus(ST_EXECUTING);
            case "done" -> run.setStatus(ST_COMPLETED);
            case "error" -> run.setStatus(ST_FAILED);
            default -> { }
        }
        run.setUpdatedAt(Instant.now());
        runMapper.updateById(run);
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

    /** 日志长文本截断（防敏感/膨胀；目标约 100~300 字符）。 */
    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }
}