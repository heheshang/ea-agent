package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.eaagent.agent.engine.AgentEngine;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentServiceTest {
    private static final long TENANT = 1L;
    private static final long USER = 2L;
    private static final String SESSION = "sess-1";

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    private static AgentRunEntity run(String status) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(99L);
        run.setTenantId(TENANT);
        run.setUserId(USER);
        run.setSessionId(SESSION);
        run.setStatus(status);
        return run;
    }

    private static AgentService service(AgentRunMapper mapper) {
        return new AgentService(mapper, new StringRedisTemplate(), List.of());
    }

    @Test
    void startRejectedWhenSameSessionHasInflightRun() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(run(AgentRunEntity.STATUS_EXECUTING));

        BizException ex = assertThrows(BizException.class,
                () -> service(mapper).startRun(TENANT, USER, "admin", "查询李四", SESSION, null));

        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.getErrorCode());
        verify(mapper, never()).insert(any(AgentRunEntity.class));
    }

    @Test
    void concurrentInsertConflictIsStateNotAllowed() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        when(mapper.insert(any(AgentRunEntity.class))).thenThrow(new DuplicateKeyException("inflight conflict"));

        BizException ex = assertThrows(BizException.class,
                () -> service(mapper).startRun(TENANT, USER, "admin", "查询李四", SESSION, null));

        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.getErrorCode());
    }

    @Test
    void ownerCanReadRunButOtherTenantOrUserCannot() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRunEntity run = run(AgentRunEntity.STATUS_COMPLETED);
        when(mapper.selectById(run.getId())).thenReturn(run);
        AgentService service = service(mapper);

        TenantContext.setIdentity(TENANT, USER, "admin");
        assertSame(run, service.getRun(run.getId()));
        TenantContext.setIdentity(TENANT + 1, USER, "admin");
        assertEquals(ErrorCode.SESSION_NOT_FOUND,
                assertThrows(BizException.class, () -> service.getRun(run.getId())).getErrorCode());
        TenantContext.setIdentity(TENANT, USER + 1, "admin");
        assertEquals(ErrorCode.SESSION_NOT_FOUND,
                assertThrows(BizException.class, () -> service.getRun(run.getId())).getErrorCode());
        TenantContext.setIdentity(TENANT, null, "admin");
        assertEquals(ErrorCode.SESSION_NOT_FOUND,
                assertThrows(BizException.class, () -> service.getRun(run.getId())).getErrorCode());
    }

    @Test
    void staleNewSnapshotCannotStartAnAlreadyClaimedRun() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentEngine engine = mock(AgentEngine.class);
        AgentService service = new AgentService(mapper, emptySteps(), List.of(engine));

        BizException ex = assertThrows(BizException.class,
                () -> service.resume(run(AgentRunEntity.STATUS_NEW), new SseEmitter()));

        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.getErrorCode());
        verifyNoInteractions(engine);
    }

    @Test
    void completedRunReplaysStoredReplyAfterStepCacheExpires() {
        AgentRunEntity run = run(AgentRunEntity.STATUS_COMPLETED);
        run.setSummary("持久化的完整回复");
        RecordingEmitter emitter = new RecordingEmitter();
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentEngine engine = mock(AgentEngine.class);

        new AgentService(mapper, emptySteps(), List.of(engine)).resume(run, emitter);

        assertEquals(List.of("text_delta", "done"), emitter.events);
        assertEquals(run.getSummary(), emitter.payloads.get(0).get("text"));
        assertEquals("COMPLETED", emitter.payloads.get(1).get("status"));
        verifyNoInteractions(mapper, engine);
    }

    @Test
    void failedRunWithoutCachedStepsStillSignalsTerminalState() {
        RecordingEmitter emitter = new RecordingEmitter();

        new AgentService(mock(AgentRunMapper.class), emptySteps(), List.of())
                .resume(run(AgentRunEntity.STATUS_FAILED), emitter);

        assertEquals(List.of("done"), emitter.events);
        assertEquals("FAILED", emitter.payloads.get(0).get("status"));
    }

    private static StringRedisTemplate emptySteps() {
        ListOperations<String, String> steps = mock(ListOperations.class);
        when(steps.range(any(String.class), eq(0L), eq(-1L))).thenReturn(List.of());
        return new StringRedisTemplate() {
            @Override
            public ListOperations<String, String> opsForList() {
                return steps;
            }
        };
    }

    private static class RecordingEmitter extends SseEmitter {
        private final List<String> events = new ArrayList<>();
        private final List<java.util.Map<String, Object>> payloads = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(part.getData()));
            String wire = frame.toString();
            events.add(wire.substring("event:".length(), wire.indexOf('\n')));
            payloads.add(com.eaagent.common.JsonUtils.readMap(wire.substring(wire.indexOf("data:") + 5).trim()));
        }
    }
}
