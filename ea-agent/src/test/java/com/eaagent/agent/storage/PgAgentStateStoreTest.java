package com.eaagent.agent.storage;

import com.eaagent.ontology.mapper.AgentScopeStateMapper;
import com.eaagent.ontology.model.AgentScopeStateEntity;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgAgentStateStore 多租户：userId → tenant 解析、slot 语义、CAS 幂等退化更新。
 */
class PgAgentStateStoreTest {

    /** 会话状态 POJO（State 是 marker 接口）。 */
    private record Slot(String name, int n) implements State {}

    @Test
    void parseTenantIdValid() {
        assertEquals(5L, PgAgentStateStore.parseTenantId("tenant-5"));
        assertEquals(123L, PgAgentStateStore.parseTenantId("tenant-123"));
    }

    @Test
    void parseTenantIdRejectsNonTenantAndInvalid() {
        assertThrows(IllegalArgumentException.class, () -> PgAgentStateStore.parseTenantId(null));
        assertThrows(IllegalArgumentException.class, () -> PgAgentStateStore.parseTenantId("user-5"));
        assertThrows(IllegalArgumentException.class, () -> PgAgentStateStore.parseTenantId("_default"));
        assertThrows(IllegalArgumentException.class, () -> PgAgentStateStore.parseTenantId("tenant-0"));
        assertThrows(IllegalArgumentException.class, () -> PgAgentStateStore.parseTenantId("tenant--5"));
        assertThrows(IllegalArgumentException.class, () -> PgAgentStateStore.parseTenantId("tenant-abc"));
    }

    @Test
    void saveSingleUsesSlotSingleAndTenantId() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        when(mapper.insert(any(AgentScopeStateEntity.class))).thenReturn(1);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        store.save("tenant-5", "s1", "k1", new Slot("a", 1));

        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((AgentScopeStateEntity e) ->
                e.getTenantId() == 5L
                        && e.getUserId().equals("tenant-5")
                        && e.getSessionId().equals("s1")
                        && e.getStateKey().equals("k1")
                        && AgentScopeStateEntity.SLOT_SINGLE.equals(e.getSlotKind())
                        && ((java.util.Map<?, ?>) e.getContent()).get("name").equals("a")
                        && ((java.util.Map<?, ?>) e.getContent()).get("n").equals(1)));
    }

    @Test
    void saveListUsesSlotListAndArrayContent() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        when(mapper.insert(any(AgentScopeStateEntity.class))).thenReturn(1);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        store.save("tenant-7", "s1", "k1", List.of(new Slot("a", 1), new Slot("b", 2)));

        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((AgentScopeStateEntity e) ->
                e.getTenantId() == 7L
                        && AgentScopeStateEntity.SLOT_LIST.equals(e.getSlotKind())));
    }

    @Test
    void saveDuplicateKeyFallsBackToUpdate() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        when(mapper.insert(any(AgentScopeStateEntity.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        when(mapper.update(any(), any())).thenReturn(1);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        store.save("tenant-5", "s1", "k1", new Slot("a", 1));
        verify(mapper).update(any(), any());
    }

    @Test
    void getReturnsSingleWhenSlotKindMatches() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        AgentScopeStateEntity row = entity(5L, "tenant-5", "s1", "k1",
                AgentScopeStateEntity.SLOT_SINGLE, Map.of("name", "a", "n", 1));
        when(mapper.selectOne(any())).thenReturn(row);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        Optional<Slot> got = store.get("tenant-5", "s1", "k1", Slot.class);
        assertTrue(got.isPresent());
        assertEquals(new Slot("a", 1), got.get());
    }

    @Test
    void getReturnsEmptyWhenSlotIsList() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        AgentScopeStateEntity row = entity(5L, "tenant-5", "s1", "k1",
                AgentScopeStateEntity.SLOT_LIST, Map.of("dummy", "x"));
        when(mapper.selectOne(any())).thenReturn(row);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        assertTrue(store.get("tenant-5", "s1", "k1", Slot.class).isEmpty());
    }

    @Test
    void getMissingReturnsEmpty() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(new PgAgentStateStore(mapper).get("tenant-5", "s1", "k1", Slot.class).isEmpty());
    }

    @Test
    void getListReturnsItemsWhenSlotIsList() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        AgentScopeStateEntity row = entity(5L, "tenant-5", "s1", "k1",
                AgentScopeStateEntity.SLOT_LIST, java.util.List.of(
                        java.util.Map.of("name", "a", "n", 1), java.util.Map.of("name", "b", "n", 2)));
        when(mapper.selectOne(any())).thenReturn(row);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        List<Slot> got = store.getList("tenant-5", "s1", "k1", Slot.class);
        assertEquals(2, got.size());
        assertEquals(new Slot("a", 1), got.get(0));
    }

    @Test
    void getListReturnsEmptyWhenSlotIsSingle() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        AgentScopeStateEntity row = entity(5L, "tenant-5", "s1", "k1",
                AgentScopeStateEntity.SLOT_SINGLE, Map.of("name", "a", "n", 1));
        when(mapper.selectOne(any())).thenReturn(row);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        assertTrue(store.getList("tenant-5", "s1", "k1", Slot.class).isEmpty());
    }

    @Test
    void existsQueriesTenantUserSession() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        assertTrue(store.exists("tenant-9", "s9"));
        verify(mapper).selectCount(any());
    }

    @Test
    void existsFalseWhenNoRow() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        assertFalse(new PgAgentStateStore(mapper).exists("tenant-9", "s9"));
    }

    @Test
    void listSessionIdsFiltersByTenantUser() {
        AgentScopeStateMapper mapper = mock(AgentScopeStateMapper.class);
        AgentScopeStateEntity r1 = new AgentScopeStateEntity();
        r1.setSessionId("s1");
        AgentScopeStateEntity r2 = new AgentScopeStateEntity();
        r2.setSessionId("s2");
        when(mapper.selectList(any())).thenReturn(List.of(r1, r2));
        PgAgentStateStore store = new PgAgentStateStore(mapper);

        assertEquals(java.util.Set.of("s1", "s2"), store.listSessionIds("tenant-5"));
    }

    private static AgentScopeStateEntity entity(long tenantId, String userId, String sessionId,
                                                String key, String slotKind, Object content) {
        AgentScopeStateEntity e = new AgentScopeStateEntity();
        e.setTenantId(tenantId);
        e.setUserId(userId);
        e.setSessionId(sessionId);
        e.setStateKey(key);
        e.setSlotKind(slotKind);
        e.setContent(content);
        return e;
    }
}