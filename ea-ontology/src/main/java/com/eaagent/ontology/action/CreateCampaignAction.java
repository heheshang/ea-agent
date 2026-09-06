package com.eaagent.ontology.action;

import com.eaagent.common.BizException;
import com.eaagent.common.Channels;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.common.Roles;
import com.eaagent.common.TriggerRuleCodec;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.service.AudienceSnapshotService;
import com.eaagent.ontology.service.TemplateRoutingService;
import com.eaagent.ontology.service.WorkflowCodec;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * createCampaign（10.4）：创建任务（OPERATOR 起，触发规则参数一次成型）；
 * 支持多通道编排：workflow 非空时按 DAG 节点执行（各节点自带 channel/template_id/condition/next，
 * 顶层 channel/template_id 缺省取首节点）；模板未创建时先调 createTemplate。
 */
@Component
public class CreateCampaignAction extends AbstractAction {

    private final CampaignMapper campaignMapper;
    private final TemplateMapper templateMapper;
    private final TemplateRoutingService templateRoutingService;
    private final AudienceSnapshotService snapshotService;

    public CreateCampaignAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                                StringRedisTemplate redis, CampaignMapper campaignMapper,
                                TemplateMapper templateMapper, TemplateRoutingService templateRoutingService,
                                AudienceSnapshotService snapshotService) {
        super(actionLogMapper, idempotencyService, redis);
        this.campaignMapper = campaignMapper;
        this.templateMapper = templateMapper;
        this.templateRoutingService = templateRoutingService;
        this.snapshotService = snapshotService;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("createCampaign")
                .description("创建触达任务（触发规则一次成型；trigger_rule 必填：含 event_type，可按需附 window；可带 workflow 节点数组做多通道编排；模板不存在时先调用 createTemplate）")
                .requiredArgs(List.of("name", "audience_id"))
                .permissions(List.of(Roles.OPERATOR))
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        CampaignEntity c = new CampaignEntity();
        c.setTenantId(ctx.tenantId());
        c.setName(req.getString("name"));
        c.setAudienceId(req.getLong("audience_id"));

        // 多通道编排 DAG：校验通过（结构/通道/模板存在/next 引用/无环）后整体落库；空列表=未启用
        List<Map<String, Object>> workflow = WorkflowCodec.validate(ctx.tenantId(), req.getList("workflow"), templateMapper);

        String channel = req.getString("channel");
        Long templateId = req.getLong("template_id");
        if (workflow != null && !workflow.isEmpty()) {
            // 顶层 column NOT NULL；workflow 活动缺省时取首节点兜底
            if (channel == null) {
                channel = String.valueOf(workflow.get(0).get("channel"));
            }
            if (templateId == null) {
                templateId = workflow.get(0).get("template_id") instanceof Number n
                        ? n.longValue() : Long.valueOf(String.valueOf(workflow.get(0).get("template_id")));
            }
        }
        if (channel == null || !Channels.ALL_SET.contains(channel)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "未知通道（应为 sms|email|wechat|push|console）: " + channel);
        }
        if (templateId == null) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "缺少 template_id（模板未创建时可先调用 createTemplate 创建）");
        }
        requireTemplate(ctx.tenantId(), templateId);

        c.setChannel(channel);
        c.setTemplateId(templateId);
        c.setSchedule(req.get("schedule") == null ? null : java.time.Instant.parse(String.valueOf(req.get("schedule"))));
        c.setCron(req.getString("cron"));
        List<Map<String, Object>> routing = req.getList("template_routing");
        templateRoutingService.validate(ctx.tenantId(), routing);
        c.setTemplateRouting(routing);
        c.setWorkflow(workflow == null || workflow.isEmpty() ? null : workflow);
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
        if (workflow != null && !workflow.isEmpty()) {
            out.put("workflow", Boolean.TRUE);
        }
        Object memberCount = c.getAudienceSnapshot() == null ? null : c.getAudienceSnapshot().get("member_count");
        out.put("audience_member_count", memberCount);
        return out;
    }

    /** 模板存在校验：不存在给出可读错误并提示先调 createTemplate（模板未创建→先建后建活动）。 */
    private void requireTemplate(Long tenantId, Long tplId) {
        TemplateEntity t = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                .eq(TemplateEntity.COL_TENANT_ID, tenantId)
                .eq(TemplateEntity.COL_ID, tplId));
        if (t == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "模板不存在: " + tplId + "（可先调用 createTemplate 创建后再建活动）");
        }
    }
}