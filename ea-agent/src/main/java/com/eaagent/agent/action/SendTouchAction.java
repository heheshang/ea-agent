package com.eaagent.agent.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.channel.ChannelAdapter;
import com.eaagent.channel.ChannelAdapterRegistry;
import com.eaagent.channel.DeliveryMessage;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.ontology.action.AbstractAction;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionMeta;
import com.eaagent.ontology.action.ActionRequest;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.mapper.UnsubscribeMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.model.UnsubscribeEntity;
import com.eaagent.ontology.service.AbBucketer;
import com.eaagent.ontology.service.AudienceResolver;
import com.eaagent.ontology.service.CooldownService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * sendTouch（10.4）：组合式发送管线 —— 人群派生 → 逐客户：退订检查 → 冷却窗 → 灰度/AB
 * 分桶 → delivery 落库（request_id 幂等）→ 通道发送（console 降级）。单客户失败不阻断整体，
 * 统计计入 skipped；发送异常抛 E-14003 由调用方（EventConsumer）裁决。
 */
@Component
public class SendTouchAction extends AbstractAction {

    private final CampaignMapper campaignMapper;
    private final AudienceMapper audienceMapper;
    private final TemplateMapper templateMapper;
    private final CustomerMapper customerMapper;
    private final DeliveryMapper deliveryMapper;
    private final UnsubscribeMapper unsubscribeMapper;
    private final AudienceResolver audienceResolver;
    private final CooldownService cooldownService;
    private final AbBucketer abBucketer;
    private final ChannelAdapterRegistry channelRegistry;

    public SendTouchAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                           StringRedisTemplate redis, CampaignMapper campaignMapper, AudienceMapper audienceMapper,
                           TemplateMapper templateMapper, CustomerMapper customerMapper,
                           DeliveryMapper deliveryMapper, UnsubscribeMapper unsubscribeMapper,
                           AudienceResolver audienceResolver, CooldownService cooldownService,
                           AbBucketer abBucketer, ChannelAdapterRegistry channelRegistry) {
        super(actionLogMapper, idempotencyService, redis);
        this.campaignMapper = campaignMapper;
        this.audienceMapper = audienceMapper;
        this.templateMapper = templateMapper;
        this.customerMapper = customerMapper;
        this.deliveryMapper = deliveryMapper;
        this.unsubscribeMapper = unsubscribeMapper;
        this.audienceResolver = audienceResolver;
        this.cooldownService = cooldownService;
        this.abBucketer = abBucketer;
        this.channelRegistry = channelRegistry;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("sendTouch")
                .description("按人群执行触达发送（退订/冷却/灰度/AB 全检查）")
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
        AudienceEntity audience = audienceMapper.selectById(campaign.getAudienceId());
        if (audience == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        TemplateEntity template = templateMapper.selectById(campaign.getTemplateId());
        if (template == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        ChannelAdapter adapter = channelRegistry.get(campaign.getChannel());
        adapter.validate(Map.of());

        Duration cooldown = null;
        String cooldownRaw = req.getString("cooldown");
        if (cooldownRaw != null && !cooldownRaw.isBlank()) {
            cooldown = Duration.parse(cooldownRaw);
        } else if (campaign.getTriggerRule() != null && campaign.getTriggerRule().get("cooldown") != null) {
            cooldown = Duration.parse(String.valueOf(campaign.getTriggerRule().get("cooldown")));
        }
        if (cooldown == null) {
            cooldown = Duration.ofHours(1);
        }

        List<CustomerEntity> customers = audienceResolver.resolve(tenantId, audience);
        int sent = 0, unsubscribed = 0, cooling = 0, graySkipped = 0, failed = 0;
        for (CustomerEntity c : customers) {
            // 1. 退订检查
            if (isUnsubscribed(tenantId, c, campaign.getChannel())) {
                unsubscribed++;
                continue;
            }
            // 2. 冷却窗（trigger 场景：事件去重窗口）
            if (!cooldownService.tryEnter(tenantId, campaign.getId(), c.getId(), cooldown)) {
                cooling++;
                continue;
            }
            // 3. 灰度 + AB 分桶
            int bucket = abBucketer.bucket(tenantId, campaign.getId(), c.getId());
            boolean grayHit = bucket < (campaign.getGrayRatio() == null ? 100 : campaign.getGrayRatio());
            if (!grayHit) {
                graySkipped++;
                continue;
            }
            String abGroup = null;
            if ("AB".equals(campaign.getAbMode())) {
                int split = campaign.getAbSplit() == null ? 0 : campaign.getAbSplit();
                int variantIdx = abBucketer.variant(bucket, campaign.getAbVariants(), split);
                abGroup = variantIdx < 0 ? "CONTROL"
                        : "TREATMENT_" + (char) ('A' + variantIdx);
            }
            // 4. delivery 落库（request_id 幂等）
            DeliveryEntity d = new DeliveryEntity();
            d.setRequestId(UUID.randomUUID().toString());
            d.setTenantId(tenantId);
            d.setCampaignId(campaign.getId());
            d.setCustomerId(c.getId());
            d.setChannel(campaign.getChannel());
            d.setTemplateId(template.getId());
            d.setGrayHit(grayHit);
            d.setAbGroup(abGroup);
            d.setStatus("PENDING");
            d.setAttempt(0);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            deliveryMapper.insert(d);
            // 5. 通道发送（console 降级：回写 SENT）
            String to = pickContact(c, campaign.getChannel());
            String content = render(template.getContent(), template.getVars(), c);
            try {
                adapter.send(new DeliveryMessage(d.getId(), tenantId, c.getId(), campaign.getChannel(),
                        to, content, abGroup));
                sent++;
            } catch (Exception e) {
                failed++;
                d.setStatus("FAILED");
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
        out.put("skipped_cooldown", cooling);
        out.put("skipped_gray", graySkipped);
        out.put("failed", failed);
        return out;
    }

    private boolean isUnsubscribed(Long tenantId, CustomerEntity c, String channel) {
        String contact = c.getPhone() != null && !c.getPhone().isBlank()
                ? c.getPhone() : c.getEmail();
        if (contact == null || contact.isBlank() || c.getWechatOpenid() == null) {
            // 无联系方式/仅 openid：跳过（避免静默漏发，计入未退订项）
        }
        if (contact == null || contact.isBlank()) {
            return false;
        }
        UnsubscribeEntity u = unsubscribeMapper.selectOne(new QueryWrapper<UnsubscribeEntity>()
                .eq(UnsubscribeEntity.COL_CUSTOMER_KEY, sha256Hex(contact)).eq(UnsubscribeEntity.COL_CHANNEL, channel).last("LIMIT 1"));
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

    /** {{var}} 简单模板替换：var 取值顺序 attributes → 实体字段。 */
    private String render(String content, List<String> vars, CustomerEntity c) {
        if (content == null) {
            return "";
        }
        String out = content;
        if (vars != null) {
            for (String v : vars) {
                Object val = c.getAttributes() == null ? null : c.getAttributes().get(v);
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

    static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}