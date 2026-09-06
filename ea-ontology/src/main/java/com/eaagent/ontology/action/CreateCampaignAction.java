package com.eaagent.ontology.action;

import com.eaagent.common.IdempotencyService;
import com.eaagent.common.Roles;
import com.eaagent.common.TriggerRuleCodec;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.service.AudienceSnapshotService;
import com.eaagent.ontology.service.TemplateRoutingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * createCampaign（10.4）：创建任务（OPERATOR 起，触发规则参数一次成型）；
 */
@Component
public class CreateCampaignAction extends AbstractAction {

    private final CampaignMapper campaignMapper;
    private final TemplateRoutingService templateRoutingService;
    private final AudienceSnapshotService snapshotService;

    public CreateCampaignAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                                StringRedisTemplate redis, CampaignMapper campaignMapper,
                                TemplateRoutingService templateRoutingService,
                                AudienceSnapshotService snapshotService) {
        super(actionLogMapper, idempotencyService, redis);
        this.campaignMapper = campaignMapper;
        this.templateRoutingService = templateRoutingService;
        this.snapshotService = snapshotService;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("createCampaign")
                .description("创建触达任务（触发规则一次成型；trigger_rule 必填：含 event_type，可按需附 window）")
                .requiredArgs(List.of("name", "audience_id", "channel", "template_id"))
                .permissions(List.of(Roles.OPERATOR))
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        CampaignEntity c = new CampaignEntity();
        c.setTenantId(ctx.tenantId());
        c.setName(req.getString("name"));
        c.setAudienceId(req.getLong("audience_id"));
        c.setChannel(req.getString("channel"));
        c.setTemplateId(req.getLong("template_id"));
        c.setSchedule(req.get("schedule") == null ? null : java.time.Instant.parse(String.valueOf(req.get("schedule"))));
        c.setCron(req.getString("cron"));
        List<Map<String, Object>> routing = req.getList("template_routing");
        templateRoutingService.validate(ctx.tenantId(), routing);
        c.setTemplateRouting(routing);
        c.setTriggerRule(TriggerRuleCodec.normalize(req.getMap("trigger_rule")));
        c.setOwnerId(ctx.userId());
        c.setStatus(CampaignEntity.STATUS_DRAFT);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        // 人群固化为快照（audience 不存在/规则非法：落库前报错，无半成品行）；触达只对快照内客户
        c.setAudienceSnapshot(snapshotService.build(ctx.tenantId(), c.getAudienceId()));
        campaignMapper.insert(c);

        Map<String, Object> out = new HashMap<>();
        out.put("campaign_id", c.getId());
        out.put("status", c.getStatus());
        Object memberCount = c.getAudienceSnapshot() == null ? null : c.getAudienceSnapshot().get("member_count");
        out.put("audience_member_count", memberCount);
        return out;
    }
}