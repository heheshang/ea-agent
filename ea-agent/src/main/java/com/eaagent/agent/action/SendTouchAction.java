package com.eaagent.agent.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.channel.ChannelAdapter;
import com.eaagent.channel.ChannelAdapterRegistry;
import com.eaagent.channel.DeliveryMessage;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.common.Texts;
import com.eaagent.ontology.action.AbstractAction;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionMeta;
import com.eaagent.ontology.action.ActionRequest;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.mapper.UnsubscribeMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.model.UnsubscribeEntity;
import com.eaagent.ontology.service.AudienceSnapshotService;
import com.eaagent.ontology.service.TemplateRoutingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * sendTouch（10.4）：组合式发送管线 —— 人群派生 → 逐客户：退订检查 →
 * delivery 落库（request_id 幂等）→ 通道发送（console 降级）。单客户失败不阻断整体，
 * 统计计入 skipped；发送异常抛 E-14003 由调用方（EventConsumer）裁决。
 */
@Component
public class SendTouchAction extends AbstractAction {

    private final CampaignMapper campaignMapper;
    private final TemplateMapper templateMapper;
    private final CustomerMapper customerMapper;
    private final DeliveryMapper deliveryMapper;
    private final UnsubscribeMapper unsubscribeMapper;
    private final AudienceSnapshotService snapshotService;
    private final ChannelAdapterRegistry channelRegistry;
    private final TemplateRoutingService templateRoutingService;

    public SendTouchAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                           StringRedisTemplate redis, CampaignMapper campaignMapper,
                           TemplateMapper templateMapper, CustomerMapper customerMapper,
                           DeliveryMapper deliveryMapper, UnsubscribeMapper unsubscribeMapper,
                           AudienceSnapshotService snapshotService,
                           ChannelAdapterRegistry channelRegistry,
                           TemplateRoutingService templateRoutingService) {
        super(actionLogMapper, idempotencyService, redis);
        this.campaignMapper = campaignMapper;
        this.templateMapper = templateMapper;
        this.customerMapper = customerMapper;
        this.deliveryMapper = deliveryMapper;
        this.unsubscribeMapper = unsubscribeMapper;
        this.snapshotService = snapshotService;
        this.channelRegistry = channelRegistry;
        this.templateRoutingService = templateRoutingService;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("sendTouch")
                .description("按人群执行触达发送（退订检查）")
                .requiredArgs(List.of("campaign_id"))
                .permissions(List.of())
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        Long tenantId = ctx.tenantId();
        CampaignEntity campaign = campaignMapper.selectOne(new QueryWrapper<CampaignEntity>()
                .eq(CampaignEntity.COL_TENANT_ID, tenantId).eq(CampaignEntity.COL_ID, req.getLong("campaign_id")));
        if (campaign == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        String eventType = req.getString("event_type");
        Map<String, Object> eventPayload = req.getMap("event_payload");
        Map<Long, TemplateEntity> tplCache = new HashMap<>();
        // 人群来源 = 活动快照（创建/换人群时固化；存量活动首次发送惰性回填），绝不实时重算
        List<Long> memberIds = snapshotService.memberIds(tenantId, campaign);
        List<CustomerEntity> customers = memberIds.isEmpty() ? List.of()
                : customerMapper.selectList(new QueryWrapper<CustomerEntity>()
                        .eq(CustomerEntity.COL_TENANT_ID, tenantId).in(CustomerEntity.COL_ID, memberIds));
        int sent = 0, unsubscribed = 0, failed = 0;
        for (CustomerEntity c : customers) {
            DeliveryEntity d = sendOneCustomer(tenantId, campaign, c, campaign.getChannel(), null,
                    null, eventType, eventPayload, tplCache);
            if (d == null) {
                unsubscribed++;
            } else if (DeliveryEntity.STATUS_SENT.equals(d.getStatus())
                    || DeliveryEntity.STATUS_DELIVERED.equals(d.getStatus())) {
                sent++;
            } else {
                failed++;
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("campaign_id", campaign.getId());
        out.put("total_customers", customers.size());
        out.put("sent", sent);
        out.put("skipped_unsubscribed", unsubscribed);
        out.put("failed", failed);
        return out;
    }

    /**
     * 单客户发送（普通活动与多通道编排 DAG 共用）：
     * - workflowNode 非空 → DAG 节点发送：按节点 channel 取适配器、按 template_id 直定模板，且模板必须
     *   APPROVED（模板未审核不可发，E-13002）；跳过 templateRouting。
     * - 否则按活动通道 + templateRouting 解析（原发送管线）。
     * delivery 落库（request_id 幂等；DAG 标记 workflow_node）→ 通道发送：成功由适配器回写 SENT；
     * 异常置 FAILED（error 截断 200）后返回。退订跳过不落库，返回 null。
     */
    public DeliveryEntity sendOneCustomer(Long tenantId, CampaignEntity campaign, CustomerEntity c,
                                          String channel, Long templateId, String workflowNode,
                                          String eventType, Map<String, Object> eventPayload,
                                          Map<Long, TemplateEntity> tplCache) {
        // 1. 退订检查
        if (isUnsubscribed(tenantId, c, channel)) {
            return null;
        }
        ChannelAdapter adapter = channelRegistry.get(channel);
        adapter.validate(tenantId, Map.of());
        // 2. 模板解析：DAG 节点直定模板（须 APPROVED）；普通活动走路由
        TemplateEntity tpl = resolveTemplate(tenantId, campaign, channel, templateId, workflowNode,
                eventType, eventPayload, c, tplCache);
        // 3. delivery 落库（request_id 幂等）
        DeliveryEntity d = new DeliveryEntity();
        d.setRequestId(UUID.randomUUID().toString());
        d.setTenantId(tenantId);
        d.setCampaignId(campaign.getId());
        d.setCustomerId(c.getId());
        d.setChannel(channel);
        d.setTemplateId(tpl.getId());
        d.setStatus(DeliveryEntity.STATUS_PENDING);
        d.setAttempt(0);
        d.setWorkflowNode(workflowNode);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(Instant.now());
        deliveryMapper.insert(d);
        // 4. 通道发送（console 降级：回写 SENT）
        String to = pickContact(c, channel);
        String content = render(tpl.getContent(), tpl.getVars(), c, eventPayload);
        try {
            adapter.send(new DeliveryMessage(d.getId(), tenantId, c.getId(), channel, to, content));
            // 适配器同步回写 DB（SENT/msg_id）；重查以让内存状态与库一致（DAG 前驱判定依赖真实状态）
            DeliveryEntity fresh = deliveryMapper.selectById(d.getId());
            if (fresh != null) {
                d = fresh;
            }
        } catch (Exception e) {
            d.setStatus(DeliveryEntity.STATUS_FAILED);
            d.setError(String.valueOf(e.getMessage()).substring(0, Math.min(200, String.valueOf(e.getMessage()).length())));
            d.setUpdatedAt(Instant.now());
            deliveryMapper.updateById(d);
        }
        return d;
    }

    private TemplateEntity resolveTemplate(Long tenantId, CampaignEntity campaign, String channel,
                                           Long templateId, String workflowNode, String eventType,
                                           Map<String, Object> eventPayload, CustomerEntity c,
                                           Map<Long, TemplateEntity> tplCache) {
        if (workflowNode != null) {
            Long tplId = templateId != null ? templateId
                    : campaign.getTemplateId() != null ? campaign.getTemplateId()
                            : campaign.getWorkflow() != null && !campaign.getWorkflow().isEmpty()
                                    ? toLong(campaign.getWorkflow().get(0).get("template_id")) : null;
            if (tplId == null) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                        "DAG 节点 " + workflowNode + " 缺少 template_id");
            }
            if (tplCache.containsKey(tplId)) {
                return tplCache.get(tplId);
            }
            TemplateEntity t = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                    .eq(TemplateEntity.COL_TENANT_ID, tenantId)
                    .eq(TemplateEntity.COL_ID, tplId));
            if (t == null) {
                throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "DAG 节点模板不存在: " + tplId);
            }
            if (!TemplateEntity.REVIEW_APPROVED.equals(t.getReviewStatus())) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                        "DAG 节点模板未审核（可能未通过人工审核）: " + tplId);
            }
            tplCache.put(tplId, t);
            return t;
        }
        return templateRoutingService.resolve(campaign, eventType, eventPayload, c, tplCache);
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(o));
    }

    private boolean isUnsubscribed(Long tenantId, CustomerEntity c, String channel) {
        String contact = c.getPhone() != null && !c.getPhone().isBlank()
                ? c.getPhone() : c.getEmail();
        if (contact == null || contact.isBlank()) {
            // 无联系方式：未命中退订表按未退订处理（是否可发由通道能力决定），避免静默漏发统计失真
            return false;
        }
        UnsubscribeEntity u = unsubscribeMapper.selectOne(new QueryWrapper<UnsubscribeEntity>()
                .eq(UnsubscribeEntity.COL_CUSTOMER_KEY, Texts.sha256Hex(contact)).eq(UnsubscribeEntity.COL_CHANNEL, channel).last("LIMIT 1"));
        return u != null;
    }

    private String pickContact(CustomerEntity c, String channel) {
        switch (channel) {
            case "sms" -> {
                return c.getPhone();
            }
            case "email" -> {
                return c.getEmail();
            }
            case "console" -> {
                return c.getPhone() != null && !c.getPhone().isBlank()
                        ? c.getPhone() : c.getEmail();
            }
            default -> {
                return c.getPhone() != null && !c.getPhone().isBlank()
                        ? c.getPhone() : c.getEmail();
            }
        }
    }

    /** {{var}} 简单模板替换：var 取值顺序 事件 payload → 客户 attributes → 实体字段。 */
    private String render(String content, List<String> vars, CustomerEntity c, Map<String, Object> eventPayload) {
        if (content == null) {
            return "";
        }
        String out = content;
        if (vars != null) {
            for (String v : vars) {
                Object val = eventPayload == null ? null : eventPayload.get(v);
                if (val == null) {
                    val = c.getAttributes() == null ? null : c.getAttributes().get(v);
                }
                if (val == null) {
                    switch (v) {
                        case "name" -> val = c.getExternalId();
                        case "phone" -> val = c.getPhone();
                        case "email" -> val = c.getEmail();
                        default -> val = "";
                    }
                }
                out = out.replace("{{" + v + "}}", String.valueOf(val));
            }
        }
        return out;
    }

    }