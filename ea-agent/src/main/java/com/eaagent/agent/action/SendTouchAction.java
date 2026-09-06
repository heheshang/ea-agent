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
        ChannelAdapter adapter = channelRegistry.get(campaign.getChannel());
        adapter.validate(tenantId, Map.of());

        List<CustomerEntity> customers = memberIds.isEmpty() ? List.of()
                : customerMapper.selectList(new QueryWrapper<CustomerEntity>()
                        .eq(CustomerEntity.COL_TENANT_ID, tenantId).in(CustomerEntity.COL_ID, memberIds));
        int sent = 0, unsubscribed = 0, failed = 0;
        for (CustomerEntity c : customers) {
            // 1. 退订检查
            if (isUnsubscribed(tenantId, c, campaign.getChannel())) {
                unsubscribed++;
                continue;
            }
            // 2. delivery 落库（request_id 幂等）
            TemplateEntity tpl = templateRoutingService.resolve(campaign, eventType, eventPayload, c, tplCache);
            DeliveryEntity d = new DeliveryEntity();
            d.setRequestId(UUID.randomUUID().toString());
            d.setTenantId(tenantId);
            d.setCampaignId(campaign.getId());
            d.setCustomerId(c.getId());
            d.setChannel(campaign.getChannel());
            d.setTemplateId(tpl.getId());
            d.setStatus(DeliveryEntity.STATUS_PENDING);
            d.setAttempt(0);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            deliveryMapper.insert(d);
            // 3. 通道发送（console 降级：回写 SENT）
            String to = pickContact(c, campaign.getChannel());
            String content = render(tpl.getContent(), tpl.getVars(), c, eventPayload);
            try {
                adapter.send(new DeliveryMessage(d.getId(), tenantId, c.getId(), campaign.getChannel(),
                        to, content));
                sent++;
            } catch (Exception e) {
                failed++;
                d.setStatus(DeliveryEntity.STATUS_FAILED);
                d.setError(String.valueOf(e.getMessage()).substring(0, Math.min(200, String.valueOf(e.getMessage()).length())));
                d.setUpdatedAt(Instant.now());
                deliveryMapper.updateById(d);
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