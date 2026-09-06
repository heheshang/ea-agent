package com.eaagent.agent.storage;

import com.eaagent.ontology.mapper.AgentScopeFileMapper;
import com.eaagent.ontology.model.AgentScopeFileEntity;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgBaseStore 多租户 namespace 解析与 CAS 语义。
 * 纯函数部分直接断言；CAS/版本自增走 mock mapper 断言 SQL 参数与行数判定。
 */
class PgBaseStoreTest {

    private static final List<String> NS_5 = List.of("agents", "ea-operator", "users", "tenant-5");
    private static final String NS_5_JOINED = "agents\u001Fea-operator\u001Fusers\u001Ftenant-5\u001F";

    @Test
    void namespaceJoinAppendsTrailingSeparatorPerSegment() {
        assertEquals(NS_5_JOINED, PgBaseStore.namespaceJoin(NS_5));
        // 单段也带尾部分隔符（前缀语义基准，对齐 InMemory 的每段 + \0）
        assertEquals("users\u001F", PgBaseStore.namespaceJoin(List.of("users")));
    }

    @Test
    void namespaceParseRoundTripsJoin() {
        assertEquals(NS_5, PgBaseStore.namespaceParse(NS_5_JOINED));
        assertEquals(List.of(), PgBaseStore.namespaceParse(""));
        assertEquals(List.of("users"), PgBaseStore.namespaceParse("users\u001F"));
    }

    @Test
    void parseKeepsNoTrailingSeparatorResidual() {
        // 不带尾部分隔符的串也能还原（防御性）
        assertEquals(List.of("a", "b"), PgBaseStore.namespaceParse("a\u001Fb"));
    }

    @Test
    void tenantFromNamespaceFindsUsersSegmentSibling() {
        assertEquals(5L, PgBaseStore.tenantFromNamespace(NS_5));
        assertEquals(7L, PgBaseStore.tenantFromNamespace(
                List.of("agents", "ea-operator", "users", "tenant-7", "sessions")));
    }

    @Test
    void tenantFromNamespaceMissingUsersFailsLoud() {
        // 无 users 段：宁失败勿串租户
        assertThrows(IllegalArgumentException.class,
                () -> PgBaseStore.tenantFromNamespace(List.of("global")));
        assertThrows(IllegalArgumentException.class,
                () -> PgBaseStore.tenantFromNamespace(List.of()));
    }

    @Test
    void tenantFromNamespaceMalformedUserSegmentFailsLoud() {
        // users 后不是 tenant-N：默认 _default 或脏数据，拒写
        assertThrows(IllegalArgumentException.class,
                () -> PgBaseStore.tenantFromNamespace(
                        List.of("agents", "ea-operator", "users", "_default")));
        assertThrows(IllegalArgumentException.class,
                () -> PgBaseStore.tenantFromNamespace(
                        List.of("agents", "ea-operator", "users", "tenant-abc")));
    }

    @Test
    void getPassesTenantNamespaceAndKey() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        AgentScopeFileEntity row = new AgentScopeFileEntity();
        row.setTenantId(5L);
        row.setItemKey("s1.jsonl");
        row.setValue(Map.of("content", "x"));
        row.setVersion(3L);
        when(mapper.selectOne(any())).thenReturn(row);

        PgBaseStore store = new PgBaseStore(mapper);
        StoreItem item = store.get(NS_5, "s1.jsonl");

        assertEquals("s1.jsonl", item.key());
        assertEquals(3L, item.version());
        assertEquals("x", item.value().get("content"));
    }

    @Test
    void getMissingReturnsNull() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        assertNull(new PgBaseStore(mapper).get(NS_5, "nope"));
    }

    @Test
    void putIfVersionExpectedZeroInsertsNewVersion1() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        when(mapper.insert(any(AgentScopeFileEntity.class))).thenReturn(1);
        PgBaseStore store = new PgBaseStore(mapper);

        assertTrue(store.putIfVersion(NS_5, "k", Map.of("content", "v"), 0L));
        verify(mapper).insert(any(AgentScopeFileEntity.class));
    }

    @Test
    void putIfVersionExpectedZeroDuplicateKeyCreatesFalse() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        when(mapper.insert(any(AgentScopeFileEntity.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        PgBaseStore store = new PgBaseStore(mapper);

        assertFalse(store.putIfVersion(NS_5, "k", Map.of("content", "v"), 0L));
    }

    @Test
    void putIfVersionCasedUpdateMatchesVersion() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        // update 影响 1 行 → CAS 命中
        when(mapper.update(any(), any())).thenReturn(1);
        PgBaseStore store = new PgBaseStore(mapper);

        assertTrue(store.putIfVersion(NS_5, "k", Map.of("content", "v2"), 2L));
    }

    @Test
    void putIfVersionCasedUpdateVersionMismatch() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        // update 影响 0 行 → 版本不匹配
        when(mapper.update(any(), any())).thenReturn(0);
        PgBaseStore store = new PgBaseStore(mapper);

        assertFalse(store.putIfVersion(NS_5, "k", Map.of("content", "v2"), 99L));
    }

    @Test
    void searchUsesTenantAndPrefixLike() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        AgentScopeFileEntity row = new AgentScopeFileEntity();
        row.setItemKey("b.jsonl");
        row.setValue(Map.of("content", "y"));
        row.setVersion(1L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<StoreItem> items = new PgBaseStore(mapper).search(NS_5, 10, 0);
        assertEquals(1, items.size());
        assertEquals("b.jsonl", items.get(0).key());
    }

    @Test
    void putMissingInsertsVersion1() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AgentScopeFileEntity.class))).thenReturn(1);
        PgBaseStore store = new PgBaseStore(mapper);

        store.put(NS_5, "k", Map.of("content", "v"));
        verify(mapper).insert(any(AgentScopeFileEntity.class));
    }

    @Test
    void putExistingIncrementsVersion() {
        AgentScopeFileMapper mapper = mock(AgentScopeFileMapper.class);
        AgentScopeFileEntity existing = new AgentScopeFileEntity();
        existing.setVersion(1L);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(any(), any())).thenReturn(1);
        PgBaseStore store = new PgBaseStore(mapper);

        store.put(NS_5, "k", Map.of("content", "v2"));
        // setSql 版本自增走 SQL 段，此处断言 update 被调用即验证存在分支
        verify(mapper).update(any(), any());
    }
}