package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** startRun 同会话在途防重：在途 run 拒绝而非静默产生重复 run（116/117 同 goal 双 run 修复）。 */
class AgentServiceTest {

    private static final long TENANT = 1L;
    private static final long USER = 2L;
    private static final String SESSION = "sess-1";

    /**
     * redis 用真实未初始化实例（不触发连接）：防重拒绝发生在任何 redis 调用之前，
     * 仅覆盖拒绝路径；放行路径（insert 后 steps 缓存清理）依赖真实 Redis，由集成验证。
     */
    private static AgentService service(AgentRunMapper mapper) {
        return new AgentService(mapper, new StringRedisTemplate(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static AgentRunEntity stubInflight(AgentRunMapper mapper, AgentRunEntity inflight) {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(inflight);
        return inflight;
    }

    @Test
    void startRejectedWhenSameSessionHasInflightRun() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRunEntity inflight = new AgentRunEntity();
        inflight.setId(99L);
        inflight.setStatus(AgentRunEntity.STATUS_EXECUTING);
        stubInflight(mapper, inflight);

        BizException ex = assertThrows(BizException.class,
                () -> service(mapper).startRun(TENANT, USER, "admin", "查询李四", SESSION));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.getErrorCode());
        verify(mapper, never()).insert(any(AgentRunEntity.class));
    }
}