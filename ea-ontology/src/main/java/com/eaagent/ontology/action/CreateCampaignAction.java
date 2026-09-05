package com.eaagent.ontology.action;

import com.eaagent.common.IdempotencyService;
import com.eaagent.common.Roles;
import com.eaagent.common.TriggerRuleCodec;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.service.TemplateRoutingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * createCampaign（10.4）：创建任务（OPERATOR 起，灰度/AB/触发规则参数一次成型）；
 * 状态初始 DRAFT → 事件驱动/调度后 RUNNING。ab_variants 占比合计 ≤ ab_split。
 */
@Component
public class CreateCampaignAction extends AbstractAction {

    private final CampaignMapper campaignMapper;
    private final TemplateRoutingService templateRoutingService;

    public CreateCampaignAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                                StringRedisTemplate redis, CampaignMapper campaignMapper,
                                TemplateRoutingService templateRoutingService) {
        super(actionLogMapper, idempotencyService, redis);
        this.campaignMapper = campaignMapper;
        this.templateRoutingService = templateRoutingService;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("createCampaign")
                .description("创建触达任务（灰度/AB/触发规则一次成型；trigger_rule 必填：含 event_type，可按需附 window/cooldown）")
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
        c.setGrayRatio(req.getInt("gray_ratio") == null ? 100 : req.getInt("gray_ratio"));
        String abMode = req.getString("ab_mode");
        c.setAbMode(abMode == null ? CampaignEntity.AB_MODE_NONE : abMode);
        c.setAbSplit(req.getInt("ab_split"));
        c.setAbVariants(req.getList("ab_variants"));
        List<Map<String, Object>> routing = req.getList("template_routing");
        templateRoutingService.validate(ctx.tenantId(), routing);
        c.setTemplateRouting(routing);
        c.setTriggerRule(TriggerRuleCodec.normalize(req.getMap("trigger_rule")));
        c.setOwnerId(ctx.userId());
        c.setStatus(CampaignEntity.STATUS_DRAFT);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignMapper.insert(c);

        Map<String, Object> out = new HashMap<>();
        out.put("campaign_id", c.getId());
        out.put("status", c.getStatus());
        return out;
    }
}