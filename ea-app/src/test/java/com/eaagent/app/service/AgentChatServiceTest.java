package com.eaagent.app.service;

import com.eaagent.common.BizException;
import com.eaagent.ontology.mapper.AgentChatMapper;
import com.eaagent.ontology.model.AgentChatEntity;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentChatServiceTest {
    @Test
    void missingChatCannotBeDescribedOrUpdated() {
        AgentChatMapper mapper = mock(AgentChatMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        var service = new AgentChatService(mapper);
        assertThrows(BizException.class, () -> service.describeFromGoal(1L, 2L, 3L, "new description"));
        verify(mapper, never()).updateById(any(AgentChatEntity.class));
    }

    @Test
    void oversizedDescriptionRejectedBeforePersistence() {
        AgentChatMapper mapper = mock(AgentChatMapper.class);
        var service = new AgentChatService(mapper);
        assertThrows(BizException.class, () -> service.createChat(1L, 2L, "x".repeat(257)));
        verifyNoInteractions(mapper);
    }
}
