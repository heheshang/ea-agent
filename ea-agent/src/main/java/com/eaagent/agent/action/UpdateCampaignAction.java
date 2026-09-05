package com.eaagent.agent.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** updateCampaign（3.4 / 4.3 编辑落库）：更新活动全编辑字段
 * （name/audience_id/channel/template_id/schedule/cron/gray_ratio/ab_mode/ab_split/ab_variants/trigger_rule），
 * 语义为「传入字段覆盖原值、缺失（null/blank）字段保留」——trigger_rule 仍按键合并，
 * ab_variants/trigger_rule 为 null 不清空；status/owner_id/created_at 不动。 */
@Component
public class UpdateCampaignAction extends AbstractAction {

    private static final Set<String> CHANNELS = Set.of("sms", "email", "wechat", "push", "console");
    private static final Set<String> AB_MODES = Set.of("NONE", "AB");

    private final CampaignMapper campaignMapper;

    public UpdateCampaignAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                                StringRedisTemplate redis, CampaignMapper campaignMapper) {
        super(actionLogMapper, idempotencyService, redis);
        this.campaignMapper = campaignMapper;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("updateCampaign")
                .description("更新活动（人群/通道/模板/灰度/AB/触发规则，传入覆盖、缺失保留）")
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
        String abMode = req.getString("ab_mode");
        if (abMode != null && !abMode.isBlank() && !AB_MODES.contains(abMode)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "ab_mode 应为 NONE|AB");
        }
        Integer grayRatio = req.getInt("gray_ratio");
        if (grayRatio != null && (grayRatio < 0 || grayRatio > 100)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "gray_ratio 需在 0-100 之间");
        }
        Integer abSplit = req.getInt("ab_split");
        if (abSplit != null && (abSplit < 1 || abSplit > 99)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "ab_split 需在 1-99 之间");
        }
        List<Map<String, Object>> variants = req.getList("ab_variants");
        if (variants != null && !variants.isEmpty()) {
            int sum = 0;
            for (Map<String, Object> v : variants) {
                Object p = v == null ? null : v.get("percent");
                sum += p instanceof Number n ? n.intValue() : 0;
            }
            int limit = abSplit != null ? abSplit : (c.getAbSplit() == null ? 100 : c.getAbSplit());
            if (sum > limit) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                        "ab_variants 占比合计 " + sum + " 超过 ab_split " + limit);
            }
        }

        // ---- 字段覆盖（null/blank 保留原值） ----
        if (name != null && !name.isBlank()) {
            c.setName(name);
        }
        Long audienceId = req.getLong("audience_id");
        if (audienceId != null) {
            c.setAudienceId(audienceId);
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
        if (grayRatio != null) {
            c.setGrayRatio(grayRatio);
        }
        if (abMode != null && !abMode.isBlank()) {
            c.setAbMode(abMode);
        }
        if (abSplit != null) {
            c.setAbSplit(abSplit);
        }
        if (variants != null && !variants.isEmpty()) {
            c.setAbVariants(variants);
        }

        // trigger_rule 按键合并：传入键覆盖、缺失键保留（null 不清空）
        Map<String, Object> rule = req.getMap("trigger_rule");
        Map<String, Object> merged = c.getTriggerRule() == null
                ? new HashMap<>() : new HashMap<>(c.getTriggerRule());
        if (rule != null) {
            merged.putAll(rule);
        }
        // 兼容顶层直传（LLM 常把 cooldown 直接放参数顶层而非 trigger_rule 对象内）
        for (String k : new String[]{"event_type", "window", "cooldown"}) {
            if (req.get(k) != null) {
                merged.put(k, String.valueOf(req.get(k)));
            }
        }
        // 保存归一：cooldown/window 宽松格式→ISO-8601，非法即报错；全量归一顺带修复存量脏值
        Map<String, Object> ruleOut = TriggerRuleCodec.normalize(merged);
        c.setTriggerRule(ruleOut);

        // ---- 落库前 AB 一致性（对齐 DB chk_campaign_ab：AB 需 ab_split 1-99 + 1-3 个变体） ----
        String effMode = (abMode != null && !abMode.isBlank()) ? abMode : c.getAbMode();
        Integer effSplit = c.getAbSplit();
        List<Map<String, Object>> effVariants = c.getAbVariants();
        if ("AB".equals(effMode) && (effSplit == null || effSplit < 1 || effSplit > 99
                || effVariants == null || effVariants.isEmpty() || effVariants.size() > 3)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "AB 模式需配置 ab_split(1-99) 与 1-3 个 ab_variants");
        }

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
        out.put("gray_ratio", c.getGrayRatio());
        out.put("ab_mode", c.getAbMode());
        out.put("ab_split", c.getAbSplit());
        out.put("ab_variants", c.getAbVariants());
        out.put("trigger_rule", ruleOut);
        out.put("status", c.getStatus());
        return out;
    }
}