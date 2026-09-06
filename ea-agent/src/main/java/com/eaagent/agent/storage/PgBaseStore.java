package com.eaagent.agent.storage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.ontology.mapper.AgentScopeFileMapper;
import com.eaagent.ontology.model.AgentScopeFileEntity;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * agentscope {@link BaseStore} 的 PostgreSQL 实现（schema 4.1 落库）。
 *
 * <p>对应 RemoteFilesystem 的 workspace 文件 KV：namespace 段以 {@code \u001F}（单位分隔符）join 存
 * 文本列（段间分隔符不能用 {@code \0}——PG text 禁 NUL），与 InMemoryStore 的
 * {@code \0} 语义一一对应（前缀匹配按段边界生效）。租户从 namespace 的 {@code "users"} 段后段解析
 * （namespace 结构 ["agents", agentId, "users", userId, route]），解析结果落显式 tenant_id 列 + FK 兜底，
 * 不依赖任何 ThreadLocal。
 */
public class PgBaseStore implements BaseStore {
    /** namespace 段 join 分隔符（单位分隔符 U+001F）。 */
    public static final char NS_SEPARATOR = '\u001F';

    private final AgentScopeFileMapper mapper;

    public PgBaseStore(AgentScopeFileMapper mapper) {
        this.mapper = mapper;
    }

    /** 多段 namespace → 存储 join 串（每段 + 分隔符，含尾部分隔符），前缀语义精确还原。 */
    public static String namespaceJoin(List<String> namespace) {
        StringBuilder sb = new StringBuilder();
        for (String seg : namespace) {
            sb.append(seg).append(NS_SEPARATOR);
        }
        return sb.toString();
    }

    /** 存储 join 串 → 段列表（去尾部分隔符）。 */
    public static List<String> namespaceParse(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null || joined.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < joined.length(); i++) {
            if (joined.charAt(i) == NS_SEPARATOR) {
                out.add(joined.substring(start, i));
                start = i + 1;
            }
        }
        if (start < joined.length()) { // 无尾部分隔符时的兜底段
            out.add(joined.substring(start));
        }
        return out;
    }

    /**
     * 从 namespace 段列表解析租户 id：找 "users" 段后的下一段，解析 "tenant-{id}"。
     * 解析失败（无 users 段 / 段不匹配 "tenant-N"）抛 {@link IllegalArgumentException}——
     * 生产路径引擎必传租户 userId，静默兜底会掩盖漏传导致跨租户串写（宁失败勿串租户）。
     */
    public static Long tenantFromNamespace(List<String> namespace) {
        for (int i = 0; i + 1 < namespace.size(); i++) {
            if ("users".equals(namespace.get(i))) {
                return PgAgentStateStore.parseTenantId(namespace.get(i + 1));
            }
        }
        throw new IllegalArgumentException("namespace 无 users 段，无法解析租户: " + namespace);
    }

    /** 从存储的 namespace join 串解析租户 id。 */
    public static Long tenantFromJoinedNamespace(String joined) {
        return tenantFromNamespace(namespaceParse(joined));
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        AgentScopeFileEntity e = mapper.selectOne(new QueryWrapper<AgentScopeFileEntity>()
                .eq(AgentScopeFileEntity.COL_TENANT_ID, tenantFromNamespace(namespace))
                .eq(AgentScopeFileEntity.COL_NAMESPACE, namespaceJoin(namespace))
                .eq(AgentScopeFileEntity.COL_ITEM_KEY, key));
        return e == null ? null : new StoreItem(key, e.getValue(), e.getVersion());
    }

    @Override
    @Transactional
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        Long tenantId = tenantFromNamespace(namespace);
        String ns = namespaceJoin(namespace);
        // 存在则版本自增更新，不存在则插入（version 1 起）
        AgentScopeFileEntity existing = mapper.selectOne(new QueryWrapper<AgentScopeFileEntity>()
                .eq(AgentScopeFileEntity.COL_TENANT_ID, tenantId)
                .eq(AgentScopeFileEntity.COL_NAMESPACE, ns)
                .eq(AgentScopeFileEntity.COL_ITEM_KEY, key));
        if (existing != null) {
            UpdateWrapper<AgentScopeFileEntity> uw = new UpdateWrapper<>();
            uw.eq(AgentScopeFileEntity.COL_TENANT_ID, tenantId)
                    .eq(AgentScopeFileEntity.COL_NAMESPACE, ns)
                    .eq(AgentScopeFileEntity.COL_ITEM_KEY, key)
                    .set(AgentScopeFileEntity.COL_VALUE, JsonUtils.getJsonCodec().toJson(value))
                    .setSql(AgentScopeFileEntity.COL_VERSION + " = " + AgentScopeFileEntity.COL_VERSION + " + 1")
                    .set(AgentScopeFileEntity.COL_UPDATED_AT, Instant.now());
            mapper.update(null, uw);
        } else {
            AgentScopeFileEntity e = baseEntity(tenantId, ns, key, value, 1L);
            try {
                mapper.insert(e);
            } catch (DuplicateKeyException dke) {
                // 并发插入冲突：退化为版本自增更新
                UpdateWrapper<AgentScopeFileEntity> uw = new UpdateWrapper<>();
                uw.eq(AgentScopeFileEntity.COL_TENANT_ID, tenantId)
                        .eq(AgentScopeFileEntity.COL_NAMESPACE, ns)
                        .eq(AgentScopeFileEntity.COL_ITEM_KEY, key)
                        .set(AgentScopeFileEntity.COL_VALUE, JsonUtils.getJsonCodec().toJson(value))
                        .setSql(AgentScopeFileEntity.COL_VERSION + " = " + AgentScopeFileEntity.COL_VERSION + " + 1")
                        .set(AgentScopeFileEntity.COL_UPDATED_AT, Instant.now());
                mapper.update(null, uw);
            }
        }
    }

    @Override
    @Transactional
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        Long tenantId = tenantFromNamespace(namespace);
        String ns = namespaceJoin(namespace);
        if (expectedVersion == 0L) {
            // 仅当 key 不存在时创建
            AgentScopeFileEntity e = baseEntity(tenantId, ns, key, value, 1L);
            try {
                mapper.insert(e);
                return true;
            } catch (DuplicateKeyException dke) {
                return false; // 已存在 → 创建失败
            }
        }
        // CAS：当前版本 == expected 才更新，版本 +1；影响行数 1 表示版本匹配
        UpdateWrapper<AgentScopeFileEntity> uw = new UpdateWrapper<>();
        uw.eq(AgentScopeFileEntity.COL_TENANT_ID, tenantId)
                .eq(AgentScopeFileEntity.COL_NAMESPACE, ns)
                .eq(AgentScopeFileEntity.COL_ITEM_KEY, key)
                .eq(AgentScopeFileEntity.COL_VERSION, expectedVersion)
                .set(AgentScopeFileEntity.COL_VALUE, JsonUtils.getJsonCodec().toJson(value))
                .setSql(AgentScopeFileEntity.COL_VERSION + " = " + AgentScopeFileEntity.COL_VERSION + " + 1")
                .set(AgentScopeFileEntity.COL_UPDATED_AT, Instant.now());
        return mapper.update(null, uw) == 1;
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        Long tenantId = tenantFromNamespace(namespace);
        String prefix = namespaceJoin(namespace);
        List<AgentScopeFileEntity> rows = mapper.selectList(new QueryWrapper<AgentScopeFileEntity>()
                .eq(AgentScopeFileEntity.COL_TENANT_ID, tenantId)
                .likeRight(AgentScopeFileEntity.COL_NAMESPACE, prefix)
                .orderByAsc(AgentScopeFileEntity.COL_ITEM_KEY)
                .last("LIMIT " + limit + " OFFSET " + offset));
        return rows.stream()
                .map(e -> new StoreItem(e.getItemKey(), e.getValue(), e.getVersion()))
                .toList();
    }

    @Override
    public void delete(List<String> namespace, String key) {
        mapper.delete(new QueryWrapper<AgentScopeFileEntity>()
                .eq(AgentScopeFileEntity.COL_TENANT_ID, tenantFromNamespace(namespace))
                .eq(AgentScopeFileEntity.COL_NAMESPACE, namespaceJoin(namespace))
                .eq(AgentScopeFileEntity.COL_ITEM_KEY, key));
    }

    private static AgentScopeFileEntity baseEntity(Long tenantId, String ns, String key,
                                                   Map<String, Object> value, long version) {
        AgentScopeFileEntity e = new AgentScopeFileEntity();
        e.setTenantId(tenantId);
        e.setNamespace(ns);
        e.setItemKey(key);
        e.setValue(value);
        e.setVersion(version);
        return e;
    }
}