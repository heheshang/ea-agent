package com.eaagent.agent.storage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.ontology.mapper.AgentScopeStateMapper;
import com.eaagent.ontology.model.AgentScopeStateEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * agentscope {@link AgentStateStore} 的 PostgreSQL 实现（schema 4.1 落库）。
 *
 * <p>以 (userId, sessionId, key) 定位状态。多租户维度：引擎把租户编码进 userId
 * （{@code "tenant-{tenantId}"}），本实现从 userId 解析租户落显式 tenant_id 列 + FK 兜底，
 * 不依赖任何 ThreadLocal——agentscope 线程无 TenantContext（E-11001 教训）。
 * 单值存单对象 JSON（slot_kind=single）；列表全量替换存数组 JSON（slot_kind=list，
 * 对齐 InMemoryAgentStateStore 契约，不做 JSONL 增量）。序列化复用 agentscope 全局 codec
 * （JsonUtils.getJsonCodec()），保证与 JsonFileAgentStateStore 数据格式一致。
 */
public class PgAgentStateStore implements AgentStateStore {
    private static final Logger log = LoggerFactory.getLogger(PgAgentStateStore.class);

    private static final String TENANT_PREFIX = "tenant-";

    private final AgentScopeStateMapper mapper;

    public PgAgentStateStore(AgentScopeStateMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 从 userId（引擎传入的 tenant-{tenantId}）解析租户 id。
     * 解析失败（null / 不匹配 / 非正数）抛 {@link IllegalArgumentException}——生产路径引擎必传
     * 租户 userId（sandbox/legacy/subagent 路径均不启用），静默兜底会掩盖漏传导致跨租户串写
     * （宁失败勿串租户）。
     */
    public static Long parseTenantId(String userId) {
        if (userId != null && userId.startsWith(TENANT_PREFIX)) {
            try {
                long id = Long.parseLong(userId.substring(TENANT_PREFIX.length()));
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignore) {
                // fallthrough to fail-loud
            }
        }
        throw new IllegalArgumentException("userId 无法解析租户（应为 tenant-{tenantId}）: " + userId);
    }

    private static long tenant(String userId) {
        return parseTenantId(userId);
    }

    /**
     * 读路径租户解析：userId 无法解析出有效租户（null/空白/非 tenant-{id} 格式）时返回 null
     * （匿名槽，agentscope 内部 slotKey 用 __anon__），而非抛错。仅供 get/getList/exists/
     * listSessionIds 等读路径用；写路径必须用 {@link #tenant(String)} 严格解析，防止无主/跨租户写入。
     */
    private static Long tenantOrNull(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return PgAgentStateStore.parseTenantId(userId);
        } catch (IllegalArgumentException badTenant) {
            return null;
        }
    }

    @Override
    @Transactional
    public void save(String userId, String sessionId, String key, State value) {
        long tenantId = tenant(userId);
        AgentScopeStateEntity e = baseEntity(tenantId, userId, sessionId, key,
                AgentScopeStateEntity.SLOT_SINGLE, toContent(value));
        upsert(e);
    }

    @Override
    @Transactional
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        long tenantId = tenant(userId);
        AgentScopeStateEntity e = baseEntity(tenantId, userId, sessionId, key,
                AgentScopeStateEntity.SLOT_LIST, toContent(values));
        upsert(e);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        AgentScopeStateEntity e = row(userId, sessionId, key);
        if (e == null || !AgentScopeStateEntity.SLOT_SINGLE.equals(e.getSlotKind())) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                JsonUtils.getJsonCodec().convertValue(e.getContent(), type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        AgentScopeStateEntity e = row(userId, sessionId, key);
        if (e == null || !AgentScopeStateEntity.SLOT_LIST.equals(e.getSlotKind())) {
            return List.of();
        }
        // TypeReference<List<T>> 会把 T 擦除成 State（抽象接口），Jackson 无法实例化；
        // 用 itemType 显式构建 List<itemType> 的 ParameterizedType 还原具体元素类型。
        java.lang.reflect.Type listOfItem = new java.lang.reflect.ParameterizedType() {
            @Override
            public java.lang.reflect.Type[] getActualTypeArguments() {
                return new java.lang.reflect.Type[] {itemType};
            }

            @Override
            public java.lang.reflect.Type getRawType() {
                return java.util.List.class;
            }

            @Override
            public java.lang.reflect.Type getOwnerType() {
                return null;
            }
        };
        @SuppressWarnings("unchecked")
        List<T> result = (List<T>) JsonUtils.getJsonCodec().convertValue(e.getContent(), listOfItem);
        return result == null ? List.of() : result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        Long tenantId = tenantOrNull(userId);
        if (tenantId == null) {
            return false; // 匿名槽无租户数据
        }
        return mapper.selectCount(new QueryWrapper<AgentScopeStateEntity>()
                .eq(AgentScopeStateEntity.COL_TENANT_ID, tenantId)
                .eq(AgentScopeStateEntity.COL_USER_ID, userId)
                .eq(AgentScopeStateEntity.COL_SESSION_ID, sessionId)) > 0;
    }

    @Override
    @Transactional
    public void delete(String userId, String sessionId) {
        mapper.delete(new QueryWrapper<AgentScopeStateEntity>()
                .eq(AgentScopeStateEntity.COL_TENANT_ID, tenant(userId))
                .eq(AgentScopeStateEntity.COL_USER_ID, userId)
                .eq(AgentScopeStateEntity.COL_SESSION_ID, sessionId));
    }

    @Override
    @Transactional
    public void delete(String userId, String sessionId, String key) {
        mapper.delete(new QueryWrapper<AgentScopeStateEntity>()
                .eq(AgentScopeStateEntity.COL_TENANT_ID, tenant(userId))
                .eq(AgentScopeStateEntity.COL_USER_ID, userId)
                .eq(AgentScopeStateEntity.COL_SESSION_ID, sessionId)
                .eq(AgentScopeStateEntity.COL_STATE_KEY, key));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Long tenantId = tenantOrNull(userId);
        if (tenantId == null) {
            return Set.of(); // 匿名槽无租户数据
        }
        return mapper.selectList(new QueryWrapper<AgentScopeStateEntity>()
                        .select(AgentScopeStateEntity.COL_SESSION_ID)
                        .eq(AgentScopeStateEntity.COL_TENANT_ID, tenantId)
                        .eq(AgentScopeStateEntity.COL_USER_ID, userId))
                .stream().map(AgentScopeStateEntity::getSessionId).collect(Collectors.toSet());
    }

    private AgentScopeStateEntity row(String userId, String sessionId, String key) {
        Long tenantId = tenantOrNull(userId);
        // 匿名槽（userId=null/空白，agentscope 内部 slotKey 用 __anon__）：读不到任何租户数据，
        // 返回空而非抛错——loadOrCreateAgentStateForSlot 等调用以 try/catch 兜底回退 fresh state，
        // 读空不会串租户。写路径（save/delete）仍 fail-loud，见 {@link #tenant(String)}。
        if (tenantId == null) {
            return null;
        }
        return mapper.selectOne(new QueryWrapper<AgentScopeStateEntity>()
                .eq(AgentScopeStateEntity.COL_TENANT_ID, tenantId)
                .eq(AgentScopeStateEntity.COL_USER_ID, userId)
                .eq(AgentScopeStateEntity.COL_SESSION_ID, sessionId)
                .eq(AgentScopeStateEntity.COL_STATE_KEY, key));
    }

    /** insert，冲突（UNIQUE(tenant,user,session,key)）退化为更新（幂等）。 */
    private void upsert(AgentScopeStateEntity e) {
        try {
            mapper.insert(e);
        } catch (DuplicateKeyException dke) {
            UpdateWrapper<AgentScopeStateEntity> uw = new UpdateWrapper<>();
            uw.eq(AgentScopeStateEntity.COL_TENANT_ID, e.getTenantId())
                    .eq(AgentScopeStateEntity.COL_USER_ID, e.getUserId())
                    .eq(AgentScopeStateEntity.COL_SESSION_ID, e.getSessionId())
                    .eq(AgentScopeStateEntity.COL_STATE_KEY, e.getStateKey())
                    .set(AgentScopeStateEntity.COL_SLOT_KIND, e.getSlotKind())
                    // content 是 jsonb：UpdateWrapper.set 不走实体 JacksonTypeHandler，Map 会被
                    // ObjectTypeHandler.setMap 按 PG hstore 绑定（无 hstore 扩展即抛
                    // "No hstore extension installed"）。序列化为 JSON 字符串，PG jsonb 列自动 cast。
                    .set(AgentScopeStateEntity.COL_CONTENT, JsonUtils.getJsonCodec().toJson(e.getContent()))
                    .set(AgentScopeStateEntity.COL_UPDATED_AT, Instant.now());
            mapper.update(null, uw);
        }
    }

    private static AgentScopeStateEntity baseEntity(long tenantId, String userId, String sessionId,
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

    /** State → jsonb 内容：single 转 Map（存单对象），list 转 List（存对象数组，对齐 InMemory 契约）。 */
    private static Object toContent(Object obj) {
        if (obj instanceof java.util.List) {
            return JsonUtils.getJsonCodec().convertValue(obj, new TypeReference<java.util.List<Object>>() {});
        }
        return JsonUtils.getJsonCodec().convertValue(obj, new TypeReference<Map<String, Object>>() {});
    }
}