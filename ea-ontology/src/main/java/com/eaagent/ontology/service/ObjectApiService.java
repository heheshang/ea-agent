package com.eaagent.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.api.dto.ObjectQueryRequest;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.common.MaskUtils;
import com.eaagent.common.PageResult;
import com.eaagent.common.PageToken;
import com.eaagent.common.Roles;
import com.eaagent.common.TenantContext;
import com.eaagent.common.Texts;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.ChannelConfigMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.mapper.EventMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.EventEntity;
import com.eaagent.ontology.rule.RuleEngine;
import com.eaagent.ontology.type.FieldDef;
import com.eaagent.ontology.type.ObjectTypeDef;
import com.eaagent.ontology.type.TypeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.time.Instant;

/**
 * 对象 API 服务（3.2）：统一搜索 / 详情 / links / stats。
 * 租户隔离：显式 query tenant_id（禁租户插件）；动态安全（9.2）：audience/campaign
 * 非 owner 仅 REVIEWER/ADMIN 可读（E-12005）。
 */
@Service
@RequiredArgsConstructor
public class ObjectApiService {

    /** 动态安全受控类型：非 owner 访问需 REVIEWER/ADMIN。 */
    public static final Set<String> OWNERSHIP_TYPES = Set.of("audience", "campaign");

    private final RuleEngine ruleEngine;
    private final CustomerMapper customerMapper;
    private final AudienceMapper audienceMapper;
    private final CampaignMapper campaignMapper;
    private final TemplateMapper templateMapper;
    private final ChannelConfigMapper channelConfigMapper;
    private final DeliveryMapper deliveryMapper;
    private final EventMapper eventMapper;

    public PageResult<Map<String, Object>> search(String type, ObjectQueryRequest req) {
        ObjectTypeDef def = TypeRegistry.get(type);
        long tenantId = TenantContext.requiredTenantId();
        int limit = req.getLimit() == null ? 20 : Math.min(Math.max(req.getLimit(), 1), 100);
        long offset = PageToken.decode(req.getPageToken());

        QueryWrapper<?> compiled = ruleEngine.compile(def, req.getFilter());
        @SuppressWarnings({"rawtypes", "unchecked"})
        QueryWrapper w = compiled;
        w.eq(DeliveryEntity.COL_TENANT_ID, tenantId);
        applyDynamicSecurityFilter(def, w);
        applyKeyword(def, w, req.getKeyword());
        applySort(def, w, req.getSort());

        // count 独立 wrapper：selectCount 会改写 select 为 COUNT(*)，与 list wrapper
        // 共享会污染 orderBy/LIMIT（PG 报 created_at must appear in GROUP BY）。
        QueryWrapper<?> countCompiled = ruleEngine.compile(def, req.getFilter());
        @SuppressWarnings({"rawtypes", "unchecked"})
        QueryWrapper countW = countCompiled;
        countW.eq(DeliveryEntity.COL_TENANT_ID, tenantId);
        applyDynamicSecurityFilter(def, countW);
        applyKeyword(def, countW, req.getKeyword());

        BaseMapper<?> mapper = mapperFor(type);
        long total = mapper.selectCount(countW);
        w.last("LIMIT " + limit + " OFFSET " + offset);
        List<?> rows = mapper.selectList(w);
        List<Map<String, Object>> items = rows.stream()
                .map(r -> project(def, r, false))
                .toList();
        long nextOffset = offset + items.size();
        String next = nextOffset < total ? PageToken.encode(nextOffset) : null;
        return new PageResult<>(items, next, total);
    }

    public Map<String, Object> get(String type, Long id) {
        ObjectTypeDef def = TypeRegistry.get(type);
        long tenantId = TenantContext.requiredTenantId();
        Object row = lookup(def, id, tenantId);
        checkDynamicSecurity(def, id, tenantId);
        return project(def, row, true);
    }

    /** 工具/Action 内部直取（服务端明文，不做掩码）——审计与发送管线使用。 */
    public <T> T getRaw(Class<T> entityCls, Long id, Long tenantId) {
        @SuppressWarnings("unchecked")
        BaseMapper<T> mapper = (BaseMapper<T>) mapperForEntity(entityCls);
        return mapper.selectOne(new QueryWrapper<T>().eq(DeliveryEntity.COL_ID, id).eq(DeliveryEntity.COL_TENANT_ID, tenantId));
    }

    /**
     * 画像更新（管理端）：仅 customer 类型，白名单字段 attributes / tags，均为整表替换。
     * 整表替换：前端编辑对话框所见即所得（删除属性/标签即生效）；与 Agent 侧
     * UpdateCustomerStateAction 的 attributes 深合并增量语义区分（运行期画像注入，互不覆盖）。
     * 非法字段直接 PARAM_ERROR，不允许部分更新混入基础列。
     */
    public Map<String, Object> update(String type, Long id, Map<String, Object> patch) {
        if (!"customer".equals(type)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        long tenantId = TenantContext.requiredTenantId();
        CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                .eq(CustomerEntity.COL_ID, id).eq(CustomerEntity.COL_TENANT_ID, tenantId));
        if (c == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        if (patch.containsKey("attributes")) {
            Object v = patch.get("attributes");
            if (!(v instanceof Map<?, ?>)) {
                throw new BizException(ErrorCode.PARAM_ERROR);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) v;
            c.setAttributes(new LinkedHashMap<>(attrs));
        }
        if (patch.containsKey("tags")) {
            Object v = patch.get("tags");
            if (!(v instanceof List<?>)) {
                throw new BizException(ErrorCode.PARAM_ERROR);
            }
            List<String> tags = new ArrayList<>();
            for (Object o : (List<?>) v) {
                if (o != null) {
                    tags.add(o.toString());
                }
            }
            c.setTags(tags);
        }
        c.setUpdatedAt(Instant.now());
        customerMapper.updateById(c);
        ObjectTypeDef def = TypeRegistry.get(type);
        return project(def, lookup(def, id, tenantId), true);
    }

    public List<Map<String, Object>> links(String type, Long id, String relation) {
        long tenantId = TenantContext.requiredTenantId();
        switch (type + "." + relation) {
            case "customer.deliveries" -> {
                QueryWrapper<DeliveryEntity> w = new QueryWrapper<>();
                w.eq(DeliveryEntity.COL_TENANT_ID, tenantId).eq(DeliveryEntity.COL_CUSTOMER_ID, id).orderByDesc(DeliveryEntity.COL_CREATED_AT);
                return deliveryMapper.selectList(w).stream().map(JsonUtils::toMap).toList();
            }
            case "customer.events" -> {
                QueryWrapper<EventEntity> w = new QueryWrapper<>();
                w.eq(EventEntity.COL_TENANT_ID, tenantId).eq(EventEntity.COL_CUSTOMER_ID, id).orderByDesc(EventEntity.COL_CREATED_AT);
                return eventMapper.selectList(w).stream().map(JsonUtils::toMap).toList();
            }
            case "campaign.deliveries" -> {
                QueryWrapper<DeliveryEntity> w = new QueryWrapper<>();
                w.eq(DeliveryEntity.COL_TENANT_ID, tenantId).eq(DeliveryEntity.COL_CAMPAIGN_ID, id).orderByDesc(DeliveryEntity.COL_CREATED_AT);
                return deliveryMapper.selectList(w).stream().map(JsonUtils::toMap).toList();
            }
            default -> throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    /** 统计：count *（总量）+ by_status 分组。 */
    public Map<String, Object> stats(String type, String op, String field) {
        ObjectTypeDef def = TypeRegistry.get(type);
        long tenantId = TenantContext.requiredTenantId();
        BaseMapper<?> mapper = mapperFor(type);

        @SuppressWarnings({"rawtypes", "unchecked"})
        QueryWrapper countW = new QueryWrapper<>();
        countW.eq(DeliveryEntity.COL_TENANT_ID, tenantId);
        long total = mapper.selectCount(countW);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("total", total);
        if ("status".equals(field) && def.field("status") != null) {
            @SuppressWarnings("rawtypes")
            QueryWrapper groupW = new QueryWrapper();
            groupW.select(DeliveryEntity.COL_STATUS, "count(*) as cnt");
            groupW.eq(DeliveryEntity.COL_TENANT_ID, tenantId);
            groupW.groupBy(DeliveryEntity.COL_STATUS);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> byStatus = mapper.selectMaps(groupW);
            out.put("by_status", byStatus);
        }
        return out;
    }

    /**
     * 批量对象行数（Ontology 图对象节点数据量）：
     * 租户隔离 + 动态安全过滤与 search 一致（audience/campaign 非 owner 仅统计可见部分）。
     */
    public Map<String, Long> tenantCounts(List<String> types) {
        long tenantId = TenantContext.requiredTenantId();
        Map<String, Long> out = new LinkedHashMap<>();
        for (String type : types) {
            ObjectTypeDef def = TypeRegistry.get(type);
            BaseMapper<?> mapper = mapperFor(type);
            @SuppressWarnings({"rawtypes", "unchecked"})
            QueryWrapper w = new QueryWrapper<>();
            w.eq(DeliveryEntity.COL_TENANT_ID, tenantId);
            applyDynamicSecurityFilter(def, w);
            out.put(type, mapper.selectCount(w));
        }
        return out;
    }

    // ---- 内部 ----

    /** 模糊查询（客户管理页搜索框）：姓名(attributes.name)/手机/邮箱/外部 ID 任一 LIKE。 */
    @SuppressWarnings("rawtypes")
    private void applyKeyword(ObjectTypeDef def, QueryWrapper w, String keyword) {
        if (!"customer".equals(def.name()) || keyword == null || keyword.isBlank()) {
            return;
        }
        String k = keyword.trim();
        w.and(q -> {
            @SuppressWarnings("rawtypes")
            QueryWrapper qw = (QueryWrapper) q;
            qw.apply("(external_id LIKE {0} OR phone LIKE {0} OR email LIKE {0} OR attributes->>'name' LIKE {0})", "%" + k + "%");
        });
    }

    @SuppressWarnings("rawtypes")
    private void applyDynamicSecurityFilter(ObjectTypeDef def, QueryWrapper w) {
        if (!OWNERSHIP_TYPES.contains(def.name()) || Roles.ADMIN_ROLES.contains(TenantContext.role())) {
            return;
        }
        Long userId = TenantContext.userId();
        if (userId != null) {
            w.and(q -> {
                @SuppressWarnings("rawtypes")
                QueryWrapper qw = (QueryWrapper) q;
                qw.eq(AudienceEntity.COL_OWNER_ID, userId);
                qw.or();
                qw.isNull(AudienceEntity.COL_OWNER_ID);
            });
        }
    }

    private void checkDynamicSecurity(ObjectTypeDef def, Long id, long tenantId) {
        if (!OWNERSHIP_TYPES.contains(def.name()) || Roles.ADMIN_ROLES.contains(TenantContext.role())) {
            return;
        }
        Map<String, Object> row = JsonUtils.toMap(lookup(def, id, tenantId));
        // 行数据键 = 实体字段名（Jackson）；owner_id 列对应 ownerId
        Object owner = row.get("ownerId");
        Long userId = TenantContext.userId();
        if (userId == null || owner == null || !owner.toString().equals(userId.toString())) {
            throw new BizException(ErrorCode.DYNAMIC_SECURITY_VIOLATION);
        }
    }

    private Object lookup(ObjectTypeDef def, Long id, long tenantId) {
        BaseMapper<?> mapper = mapperFor(def.name());
        @SuppressWarnings("rawtypes")
        QueryWrapper w = new QueryWrapper();
        w.eq(DeliveryEntity.COL_ID, id);
        w.eq(DeliveryEntity.COL_TENANT_ID, tenantId);
        Object row = mapper.selectOne(w);
        if (row == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        return row;
    }

    @SuppressWarnings("rawtypes")
    private void applySort(ObjectTypeDef def, QueryWrapper w, String sort) {
        if (sort == null || sort.isBlank()) {
            w.orderByDesc(DeliveryEntity.COL_CREATED_AT);
            return;
        }
        String field = sort.startsWith("-") ? sort.substring(1) : sort;
        if (!def.isQueryable(field)) {
            throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
        String column = Texts.toSnake(field);
        w.orderBy(true, !sort.startsWith("-"), column);
    }

    private Map<String, Object> project(ObjectTypeDef def, Object row, boolean details) {
        Map<String, Object> src = JsonUtils.toMap(row);
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldDef f : def.fields()) {
            if (!details && !f.queryable()) {
                continue;
            }
            Object v = src.get(Texts.toCamel(f.name()));
            if (v == null) {
                continue;
            }
            out.put(f.name(), f.sensitive() && v instanceof String s ? MaskUtils.maskByKey(f.name(), s) : v);
        }
        // jsonb 大容器：attributes / payload / trigger_rule / workflow 全量投影（键级掩码由 JsonMasker 统一递归）
        // JsonUtils.toMap 输出驼峰 key（Jackson convertValue），API 契约用下划线名
        for (String[] entry : new String[][]{
                {"attributes", "attributes"}, {"payload", "payload"},
                {"triggerRule", "trigger_rule"}, {"frequencyLimit", "frequency_limit"}, {"tags", "tags"},
                {"audienceSnapshot", "audience_snapshot"}, {"workflow", "workflow"}}) {
            Object v = src.get(entry[0]);
            if (v != null) {
                out.put(entry[1], v);
            }
        }
        return JsonMasker.mask(out);
    }

    private BaseMapper<?> mapperFor(String type) {
        return mapperForEntity(TypeRegistry.get(type).entityCls());
    }

    private BaseMapper<?> mapperForEntity(Class<?> cls) {
        return switch (cls.getSimpleName()) {
            case "CustomerEntity" -> customerMapper;
            case "AudienceEntity" -> audienceMapper;
            case "CampaignEntity" -> campaignMapper;
            case "TemplateEntity" -> templateMapper;
            case "ChannelConfigEntity" -> channelConfigMapper;
            case "DeliveryEntity" -> deliveryMapper;
            case "EventEntity" -> eventMapper;
            default -> throw new BizException(ErrorCode.TYPE_UNKNOWN);
        };
    }

    // 分隔：投影工具
}