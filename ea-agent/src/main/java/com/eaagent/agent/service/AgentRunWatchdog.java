package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 挂起 run 兜底回收（修复：LLM 调用无返回时 run 永久停在 EXECUTING/PLANNING/OBSERVING，
 * 占住会话，用户只能重复提交产生重复 run——实证 run 110/114/116）。
 *
 * 扫描 updated_at 超过 {@code ea.agent.stale-run-ms} 未推进的未终态 run：
 * <ul>
 *   <li>NEW —— 建了 run 但从未挂载执行 → CANCELLED（释放占位）；</li>
 *   <li>PLANNING/EXECUTING/OBSERVING —— 疑似执行线程挂死（LLM 超时兜底之外的最后防线，
 *       正常 run 心跳持续推进 updated_at）→ FAILED（会话可复用，避免只能靠重复提交）。</li>
 * </ul>
 * AWAITING_APPROVAL 不在此列（等待人工审批，非挂起；超时由审批超时逻辑负责）。
 */
@Component
public class AgentRunWatchdog {
    private static final Logger log = LoggerFactory.getLogger(AgentRunWatchdog.class);

    private final AgentRunMapper runMapper;
    /** 扫描周期（ms）。 */
    private final long watchdogMs;
    /** 无心跳判定阈值（ms）。 */
    private final long staleAfterMs;

    public AgentRunWatchdog(
            AgentRunMapper runMapper,
            @Value("${ea.agent.watchdog-ms:60000}") long watchdogMs,
            @Value("${ea.agent.stale-run-ms:900000}") long staleAfterMs) {
        this.runMapper = runMapper;
        this.watchdogMs = watchdogMs;
        this.staleAfterMs = Math.max(staleAfterMs, 0);
    }

    @Scheduled(fixedDelayString = "${ea.agent.watchdog-ms:60000}")
    public void sweep() {
        if (staleAfterMs <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMillis(staleAfterMs));
        List<AgentRunEntity> stale;
        try {
            stale = runMapper.selectList(new QueryWrapper<AgentRunEntity>()
                    .in(AgentRunEntity.COL_STATUS,
                            AgentService.ST_NEW, AgentService.ST_PLANNING,
                            AgentService.ST_EXECUTING, AgentService.ST_OBSERVING)
                    .lt(AgentRunEntity.COL_UPDATED_AT, cutoff)
                    .last("LIMIT 100"));
        } catch (Exception e) {
            log.warn("watchdog scan failed: {}", e.toString());
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        for (AgentRunEntity run : stale) {
            boolean neverStarted = AgentService.ST_NEW.equals(run.getStatus());
            String target = neverStarted ? AgentService.ST_CANCELLED : AgentService.ST_FAILED;
            try {
                runMapper.update(null, new UpdateWrapper<AgentRunEntity>()
                        .eq(AgentRunEntity.COL_ID, run.getId())
                        .eq(AgentRunEntity.COL_STATUS, run.getStatus())
                        .set(AgentRunEntity.COL_STATUS, target)
                        .set(AgentRunEntity.COL_UPDATED_AT, Instant.now()));
                log.warn("watchdog sweep runId={} sessionId={} status={}->{} staleMs={}",
                        run.getId(), run.getSessionId(), run.getStatus(), target,
                        Duration.between(run.getUpdatedAt(), Instant.now()).toMillis());
            } catch (Exception e) {
                log.warn("watchdog sweep failed runId={}: {}", run.getId(), e.toString());
            }
        }
    }
}