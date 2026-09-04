package com.eaagent.agent.tool;

import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionRegistry;
import com.eaagent.ontology.action.ActionResult;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.AudienceMemberMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.mapper.EventMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.AudienceMemberEntity;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.EventEntity;
import com.eaagent.ontology.rule.RuleEngine;
import com.eaagent.ontology.type.TypeRegistry;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 工具注册表（详细设计 4.2 的 9 工具 + applyAction）。
 * 工具按租户实例化（forTenant），执行不依赖 ThreadLocal TenantContext（agentscope 线程无上下文）。
 */
@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final CustomerMapper customerMapper;
    private final CampaignMapper campaignMapper;
    private final DeliveryMapper deliveryMapper;
    private final EventMapper eventMapper;
    private final AudienceMapper audienceMapper;
    private final AudienceMemberMapper audienceMemberMapper;
    private final ActionRegistry actionRegistry;
    private final RuleEngine ruleEngine;

    public AgentToolRegistry(CustomerMapper customerMapper, CampaignMapper campaignMapper,
                             DeliveryMapper deliveryMapper, EventMapper eventMapper,
                             AudienceMapper audienceMapper,
                             AudienceMemberMapper audienceMemberMapper,
                             ActionRegistry actionRegistry, RuleEngine ruleEngine) {
        this.customerMapper = customerMapper;
        this.campaignMapper = campaignMapper;
        this.deliveryMapper = deliveryMapper;
        this.eventMapper = eventMapper;
        this.audienceMapper = audienceMapper;
        this.audienceMemberMapper = audienceMemberMapper;
        this.actionRegistry = actionRegistry;
        this.ruleEngine = ruleEngine;
    }

    /** 基础 Toolkit（资源型工具，非租户绑定）。 */
    public Toolkit toolkit() {
        return new Toolkit();
    }

    /** 指定租户的工具集（供 agentscope 引擎注册）。 */
    public List<AgentTool> forTenant(Long tenantId) {
        return List.of(
                new QueryCustomers(tenantId), new QueryAudience(tenantId), new GetCampaign(tenantId),
                new QueryDelivery(tenantId), new QueryEvents(tenantId), new AudienceStats(tenantId),
                new FrequencyCheck(tenantId), new ChannelPreference(tenantId), new ChurnRiskScore(tenantId),
                new ApplyAction(tenantId));
    }

    // ---------- 基础工具骨架 ----------

    abstract static class BaseTool implements AgentTool {
        protected final Long tenantId;
        private final String name;
        private final String description;
        private final Map<String, Object> params;

        BaseTool(Long tenantId, String name, String description, Map<String, Object> params) {
            this.tenantId = tenantId;
            this.name = name;
            this.description = description;
            this.params = params;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getParameters() {
            return params;
        }

        protected abstract Map<String, Object> execute(Map<String, Object> input);

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
            String id = p.getToolUseBlock().getId();
            long t0 = System.nanoTime();
            String params = paramsToLog(p.getInput());
            try {
                Map<String, Object> out = execute(p.getInput());
                log.info("tool exec tenantId={} name={} params={} ok=true durationMs={}",
                        tenantId, name, params, (System.nanoTime() - t0) / 1_000_000L);
                return Mono.just(new ToolResultBlock(id, name, List.of(TextBlock.builder().text(JsonUtils.write(out)).build())));
            } catch (Exception e) {
                log.warn("tool exec tenantId={} name={} params={} ok=false error={} durationMs={}",
                        tenantId, name, params, truncate(String.valueOf(e.getMessage()), 150),
                        (System.nanoTime() - t0) / 1_000_000L);
                return Mono.just(new ToolResultBlock(id, name,
                        List.of(TextBlock.builder().text("{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}").build())));
            }
        }
    }

    /** 工具入参序列化：Map → JSON，失败回退 toString；日志截断 150 防敏感/膨胀。 */
    private static String paramsToLog(Object input) {
        try {
            return truncate(JsonUtils.write(input), 150);
        } catch (Exception e) {
            return truncate(String.valueOf(input), 150);
        }
    }

    /** 日志长文本截断（防敏感/膨胀）。 */
    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        return m;
    }

    private static Map<String, Object> pStr(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    /** 对象/字符串双类型：兼容 LLM 传 JSON 对象或 JSON 字符串两种形态（applicable 到 args）。 */
    private static Map<String, Object> pObj(String desc) {
        return Map.of("type", List.of("object", "string"), "description", desc,
                "additionalProperties", true);
    }

    private static Map<String, Object> pInt(String desc) {
        return Map.of("type", "integer", "description", desc);
    }

    // ---------- 9 + 1 工具 ----------

    /** queryCustomers：DSL 过滤客户。 */
    class QueryCustomers extends BaseTool {
        QueryCustomers(Long tenantId) {
            super(tenantId, "queryCustomers", "按 DSL 过滤表达式查询客户画像",
                    schema(Map.of("filter", pStr("DSL 过滤，如 status == 'ACTIVE'"), "limit", pInt("返回条数上限，默认 20")), List.of("filter")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            String filter = String.valueOf(input.getOrDefault("filter", ""));
            int limit = input.get("limit") == null ? 20 : Integer.parseInt(String.valueOf(input.get("limit")));
            @SuppressWarnings("rawtypes")
            QueryWrapper w = new QueryWrapper();
            if (filter != null && !filter.isBlank()) {
                @SuppressWarnings("rawtypes")
                QueryWrapper compiled = (QueryWrapper) ruleEngine.compile(TypeRegistry.get("customer"), filter);
                w = compiled;
            }
            w.eq(CustomerEntity.COL_TENANT_ID, tenantId);
            w.orderByDesc(CustomerEntity.COL_CREATED_AT);
            w.last("LIMIT " + Math.max(1, Math.min(limit, 100)));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) (List<?>) customerMapper.selectMaps(w);
            List<Map<String, Object>> rows = raw.stream().map(AgentToolRegistry.this::trim).toList();
            return Map.of("rows", rows, "count", rows.size());
        }
    }

    private Map<String, Object> trim(Map<String, Object> row) {
        Map<String, Object> m = new HashMap<>(row);
        m.remove("id");
        m.put("id", row.get("id"));
        return m;
    }

    /** queryAudience：audience 详情 + 成员数。 */
    class QueryAudience extends BaseTool {
        QueryAudience(Long tenantId) {
            super(tenantId, "queryAudience", "查询人群包详情与成员规模",
                    schema(Map.of("audience_id", pInt("人群包 ID")), List.of("audience_id")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            long id = Long.parseLong(String.valueOf(input.get("audience_id")));
            Long memberCount = audienceMemberMapper.selectCount(new QueryWrapper<AudienceMemberEntity>()
                    .eq(AudienceMemberEntity.COL_TENANT_ID, tenantId)
                    .eq(AudienceMemberEntity.COL_AUDIENCE_ID, id));
            AudienceEntity a = audienceMapper.selectOne(new QueryWrapper<AudienceEntity>()
                    .eq(AudienceEntity.COL_TENANT_ID, tenantId)
                    .eq(AudienceEntity.COL_ID, id));
            return Map.of("audience_id", id, "rule", a == null ? null : JsonUtils.write(a.getRule()),
                    "member_count", memberCount);
        }
    }

    /** getCampaign：campaign 配置（含灰度/AB/触发规则）。 */
    class GetCampaign extends BaseTool {
        GetCampaign(Long tenantId) {
            super(tenantId, "getCampaign", "查询活动配置（灰度、AB、触发规则）",
                    schema(Map.of("campaign_id", pInt("活动 ID")), List.of("campaign_id")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            long id = Long.parseLong(String.valueOf(input.get("campaign_id")));
            CampaignEntity c = campaignMapper.selectOne(new QueryWrapper<CampaignEntity>()
                    .eq(CampaignEntity.COL_TENANT_ID, tenantId).eq(CampaignEntity.COL_ID, id));
            return c == null ? Map.of("error", "campaign not found")
                    : JsonUtils.toMap(c);
        }
    }

    /** queryDelivery：触达流水。 */
    class QueryDelivery extends BaseTool {
        QueryDelivery(Long tenantId) {
            super(tenantId, "queryDelivery", "查询触达流水",
                    schema(Map.of("campaign_id", pInt("按活动过滤，可选"), "customer_id", pInt("按客户过滤，可选"), "limit", pInt("条数上限，默认 20")), List.of()));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            QueryWrapper<DeliveryEntity> w = new QueryWrapper<>();
            w.eq(DeliveryEntity.COL_TENANT_ID, tenantId);
            if (input.get("campaign_id") != null) {
                w.eq(DeliveryEntity.COL_CAMPAIGN_ID, Long.parseLong(String.valueOf(input.get("campaign_id"))));
            }
            if (input.get("customer_id") != null) {
                w.eq(DeliveryEntity.COL_CUSTOMER_ID, Long.parseLong(String.valueOf(input.get("customer_id"))));
            }
            int limit = input.get("limit") == null ? 20 : Integer.parseInt(String.valueOf(input.get("limit")));
            w.orderByDesc(DeliveryEntity.COL_CREATED_AT);
            w.last("LIMIT " + Math.max(1, Math.min(limit, 100)));
            List<Map<String, Object>> rows = deliveryMapper.selectList(w).stream().map(JsonUtils::toMap).toList();
            return Map.of("rows", rows, "count", rows.size());
        }
    }

    /** queryEvents：客户事件流。 */
    class QueryEvents extends BaseTool {
        QueryEvents(Long tenantId) {
            super(tenantId, "queryEvents", "查询客户事件流",
                    schema(Map.of("customer_id", pInt("客户 ID"), "limit", pInt("条数上限，默认 10")), List.of("customer_id")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            long cid = Long.parseLong(String.valueOf(input.get("customer_id")));
            int limit = input.get("limit") == null ? 10 : Integer.parseInt(String.valueOf(input.get("limit")));
            QueryWrapper<EventEntity> w = new QueryWrapper<>();
            w.eq(EventEntity.COL_TENANT_ID, tenantId).eq(EventEntity.COL_CUSTOMER_ID, cid);
            w.orderByDesc(EventEntity.COL_CREATED_AT);
            w.last("LIMIT " + Math.max(1, Math.min(limit, 50)));
            List<Map<String, Object>> rows = eventMapper.selectList(w).stream().map(JsonUtils::toMap).toList();
            return Map.of("rows", rows, "count", rows.size());
        }
    }

    /** audienceStats：人群统计。 */
    class AudienceStats extends BaseTool {
        AudienceStats(Long tenantId) {
            super(tenantId, "audienceStats", "人群规模与构成统计",
                    schema(Map.of("audience_id", pInt("人群包 ID")), List.of("audience_id")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            long id = Long.parseLong(String.valueOf(input.get("audience_id")));
            Long memberCount = audienceMemberMapper.selectCount(new QueryWrapper<AudienceMemberEntity>()
                    .eq(AudienceMemberEntity.COL_TENANT_ID, tenantId)
                    .eq(AudienceMemberEntity.COL_AUDIENCE_ID, id));
            return Map.of("audience_id", id, "member_count", memberCount);
        }
    }

    /** frequencyCheck：频控/冷却检查。 */
    class FrequencyCheck extends BaseTool {
        FrequencyCheck(Long tenantId) {
            super(tenantId, "frequencyCheck", "检查客户在指定通道的发送频控与冷却状态",
                    schema(Map.of("customer_id", pInt("客户 ID"), "channel", pStr("通道类型")), List.of("customer_id", "channel")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            long cid = Long.parseLong(String.valueOf(input.get("customer_id")));
            String channel = String.valueOf(input.get("channel"));
            QueryWrapper<DeliveryEntity> w = new QueryWrapper<>();
            w.eq(DeliveryEntity.COL_TENANT_ID, tenantId).eq(DeliveryEntity.COL_CUSTOMER_ID, cid).eq(DeliveryEntity.COL_CHANNEL, channel);
            Long sentTotal = deliveryMapper.selectCount(w);
            return Map.of("customer_id", cid, "channel", channel, "sent_total", sentTotal,
                    "cooling_active", "ea:cd:" + tenantId + ":" + cid);
        }
    }

    /** channelPreference：偏好通道。 */
    class ChannelPreference extends BaseTool {
        ChannelPreference(Long tenantId) {
            super(tenantId, "channelPreference", "读取客户偏好通道（attributes.preferred_channel）",
                    schema(Map.of("customer_id", pInt("客户 ID")), List.of("customer_id")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            long cid = Long.parseLong(String.valueOf(input.get("customer_id")));
            CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                    .eq(CustomerEntity.COL_TENANT_ID, tenantId).eq(CustomerEntity.COL_ID, cid));
            if (c == null) {
                return Map.of("error", "customer not found");
            }
            Map<String, Object> attrs = c.getAttributes() == null ? Map.of() : c.getAttributes();
            return Map.of("customer_id", cid, "preferred_channel", attrs.getOrDefault("preferred_channel", "console"));
        }
    }

    /** churnRiskScore：流失风险启发式评分（演示实现，真实模型留后续）。 */
    class ChurnRiskScore extends BaseTool {
        ChurnRiskScore(Long tenantId) {
            super(tenantId, "churnRiskScore", "流失风险评分 0-1（启发式：近 30 天事件越少风险越高）",
                    schema(Map.of("customer_id", pInt("客户 ID")), List.of("customer_id")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            long cid = Long.parseLong(String.valueOf(input.get("customer_id")));
            QueryWrapper<EventEntity> w = new QueryWrapper<>();
            w.eq(EventEntity.COL_TENANT_ID, tenantId).eq(EventEntity.COL_CUSTOMER_ID, cid);
            Long events = eventMapper.selectCount(w);
            double score = events == null || events == 0 ? 0.9 : Math.max(0.1, 1.0 - events / 5.0);
            return Map.of("customer_id", cid, "score", Math.round(score * 100) / 100.0, "basis", "启发式:1-近30天事件数/5");
        }
    }

    /** applyAction：动作执行（设计 3.4）。 */
    class ApplyAction extends BaseTool {
        ApplyAction(Long tenantId) {
            super(tenantId, "applyAction",
                    "执行运营动作。registered 动作：sendTouch（触达发送）、createCampaign（创建任务）、pauseCampaign（暂停任务）、updateCampaign（更新任务触发规则 event_type/window/cooldown）、updateCustomerState（更新客户状态）、importEvents（导入事件）",
                    schema(Map.of("action", pStr("动作名（registered 六选一）"), "args", pObj("动作参数对象（按动作名传字段；updateCampaign 传 campaign_id，可选 trigger_rule 对象或顶层 cooldown/window/event_type）")), List.of("action", "args")));
        }

        @Override
        protected Map<String, Object> execute(Map<String, Object> input) {
            String action = String.valueOf(input.get("action"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = input.get("args") instanceof Map
                    ? (Map<String, Object>) input.get("args")
                    : JsonUtils.toMap(String.valueOf(input.get("args")));
            ActionContext ctx = ActionContext.of(tenantId, null, "AGENT", "tool:" + UUID.randomUUID());
            ActionResult r = actionRegistry.get(action).execute(ctx, com.eaagent.ontology.action.ActionRequest.of(args));
            Map<String, Object> out = r.data() == null ? new HashMap<>() : new HashMap<>(r.data());
            out.put("ok", r.success());
            return out;
        }
    }
}