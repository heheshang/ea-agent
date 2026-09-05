package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 挂起 run 兜底回收：超时无心跳的 NEW→CANCELLED、执行中→FAILED，仅命中陈旧 run。 */
class AgentRunWatchdogTest {

    private static AgentRunEntity run(long id, String status, Instant updatedAt) {
        AgentRunEntity r = new AgentRunEntity();
        r.setId(id);
        r.setSessionId("s-" + id);
        r.setStatus(status);
        r.setUpdatedAt(updatedAt);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static AgentRunMapper staleMapper(List<AgentRunEntity> stale) {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(stale);
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);
        return mapper;
    }

    @Test
    void executingBecomesFailedAndNewBecomesCancelled() {
        Instant old = Instant.now().minusSeconds(3600);
        AgentRunMapper mapper = staleMapper(List.of(
                run(1L, AgentRunEntity.STATUS_EXECUTING, old),
                run(2L, AgentRunEntity.STATUS_NEW, old)));

        new AgentRunWatchdog(mapper, 60_000, 900_000).sweep();

        // UpdateWrapper.set 参数化：目标状态在参数值中（executing→FAILED、new→CANCELLED）
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper> uw =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(mapper, times(2)).update(any(), uw.capture());
        List<Object> setValues = uw.getAllValues().stream()
                .flatMap(w -> w.getParamNameValuePairs().values().stream())
                .toList();
        assertTrue(setValues.contains(AgentRunEntity.STATUS_FAILED), "执行中 run 应回收为 FAILED");
        assertTrue(setValues.contains(AgentRunEntity.STATUS_CANCELLED), "从未执行 run 应回收为 CANCELLED");
    }

    @Test
    void freshRunsAreNotSwept() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        new AgentRunWatchdog(mapper, 60_000, 900_000).sweep();

        verify(mapper, never()).update(any(), any(Wrapper.class));
    }
}