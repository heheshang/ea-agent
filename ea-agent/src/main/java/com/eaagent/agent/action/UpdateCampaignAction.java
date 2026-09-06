package com.eaagent.agent.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.Channels;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.common.TriggerRuleCodec;
import com.eaagent.ontology.action.AbstractAction;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionMeta;
import com.eaagent.ontology.action.ActionRequest;
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
import java.util.Set;

/** updateCampaign（3.4 / 4.3 编辑落库）：更新活动全编辑字段
 * （name/audience_id/channel/template_id/schedule/cron/trigger_rule），
 * 语义为「传入字段覆盖原值、缺失（null/blank）字段保留」——trigger_rule 仍按键合并，
 * trigger_rule 为 null 不清空；status/owner_id/created_at 不动。 */
@Component
public class UpdateCampaignAction extends AbstractAction {

    private static final Set<String> CHANNELS = Channels.ALL_SET;

    private final CampaignMapper campaignMapper;
    private final TemplateRoutingService templateRoutingService;
    private final AudienceSnapshotService snapshotService;

    public UpdateCampaignAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
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
                .name("updateCampaign")
                .description("更新活动（人群/通道/模板/触发规则，传入覆盖、缺失保留）")
                .requiredArgs(List.of("campaign_id"))
                .permissions(List.of())
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        CampaignEntity c = campaignMapper.selectOne(new QueryWrapper<CampaignEntity>()
                .eq(CampaignEntity.COL_TENANT_ID, ctx.tenantId()).eq(CampaignEntity.COL_ID, req.getLong("campaign_id")));
        if (c == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }

        // ---- 参数校验（传入才校验；缺失不覆盖） ----
        String name = req.getString("name");
        String channel = req.getString("channel");
        if (channel != null && !channel.isBlank() && !CHANNELS.contains(channel)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "未知通道（应为 sms|email|wechat|push|console）: " + channel);
        }
        // ---- 字段覆盖（null/blank 保留原值） ----
        if (name != null && !name.isBlank()) {
            c.setName(name);
        }
        Long audienceId = req.getLong("audience_id");
        if (audienceId != null && !audienceId.equals(c.getAudienceId())) {
            // 换人群 → 重新固化快照（同值重复提交保留原快照，范围不漂移）
            c.setAudienceId(audienceId);
            c.setAudienceSnapshot(snapshotService.build(ctx.tenantId(), audienceId));
        }
        if (channel != null && !channel.isBlank()) {
            c.setChannel(channel);
        }
        Long templateId = req.getLong("template_id");
        if (templateId != null) {
            c.setTemplateId(templateId);
        }
        if (req.get("schedule") != null) {
            c.setSchedule(Instant.parse(String.valueOf(req.get("schedule"))));
        }
        String cron = req.getString("cron");
        if (cron != null && !cron.isBlank()) {
            c.setCron(cron);
        }
        // template_routing 整表覆盖：传入（含空列表清空）才覆盖，缺失保留
        if (req.get("template_routing") != null) {
            List<Map<String, Object>> routing = req.getList("template_routing");
            templateRoutingService.validate(ctx.tenantId(), routing);
            c.setTemplateRouting(routing);
        }

        // trigger_rule 按键合并：传入键覆盖、缺失键保留（null 不清空）
        Map<String, Object> rule = req.getMap("trigger_rule");
        Map<String, Object> merged = c.getTriggerRule() == null
                ? new HashMap<>() : new HashMap<>(c.getTriggerRule());
        if (rule != null) {
            merged.putAll(rule);
        }
        // 兼容顶层直传（LLM 常把 window 直接放参数顶层而非 trigger_rule 对象内）
        for (String k : new String[]{"event_type", "window"}) {
            if (req.get(k) != null) {
                merged.put(k, String.valueOf(req.get(k)));
            }
        }
        // 保存归一：window 宽松格式→ISO-8601，非法即报错；全量归一顺带修复存量脏值
        Map<String, Object> ruleOut = TriggerRuleCodec.normalize(merged);
        c.setTriggerRule(ruleOut);

        c.setUpdatedAt(Instant.now());
        campaignMapper.updateById(c);

        Map<String, Object> out = new HashMap<>();
        out.put("campaign_id", c.getId());
        out.put("name", c.getName());
        out.put("audience_id", c.getAudienceId());
        out.put("channel", c.getChannel());
        out.put("template_id", c.getTemplateId());
        out.put("schedule", c.getSchedule() == null ? null : c.getSchedule().toString());
        out.put("cron", c.getCron());
        out.put("template_routing", c.getTemplateRouting());
        out.put("trigger_rule", ruleOut);
        out.put("status", c.getStatus());
        return out;
    }
}