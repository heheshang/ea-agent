package com.eaagent.app.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.api.dto.TemplateWriteRequest;
import com.eaagent.common.BizException;
import com.eaagent.common.Channels;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Roles;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.TemplateEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板管理（/api/templates）：租户维度 CRUD + 审核流（DRAFT|REJECTED →submit→ PENDING
 * →approve/reject→ APPROVED|REJECTED）。vars 由 content 的 {{占位符}} 自动提取；
 * 编辑仅限 DRAFT|REJECTED；删除须无活动引用（主模板或路由模板）。
 */
@Service
public class TemplateService {

    private static final Set<String> CHANNELS = Channels.ALL_SET;
    private static final Pattern VAR = Pattern.compile("\\{\\{([^{}]+)}}");

    private final TemplateMapper templateMapper;
    private final CampaignMapper campaignMapper;

    public TemplateService(TemplateMapper templateMapper, CampaignMapper campaignMapper) {
        this.templateMapper = templateMapper;
        this.campaignMapper = campaignMapper;
    }

    public List<TemplateEntity> list(Long tenantId, String channel) {
        QueryWrapper<TemplateEntity> qw = new QueryWrapper<TemplateEntity>()
                .eq(TemplateEntity.COL_TENANT_ID, tenantId)
                .orderByAsc(TemplateEntity.COL_ID);
        if (channel != null && !channel.isBlank()) {
            qw.eq(TemplateEntity.COL_CHANNEL, channel);
        }
        return templateMapper.selectList(qw);
    }

    public TemplateEntity get(Long tenantId, Long id) {
        TemplateEntity t = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                .eq(TemplateEntity.COL_TENANT_ID, tenantId).eq(TemplateEntity.COL_ID, id));
        if (t == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "模板不存在: " + id);
        }
        return t;
    }

    public TemplateEntity create(Long tenantId, Long userId, TemplateWriteRequest req) {
        validate(req);
        TemplateEntity t = new TemplateEntity();
        t.setTenantId(tenantId);
        t.setChannel(req.getChannel());
        t.setTitle(req.getTitle());
        t.setContent(req.getContent());
        t.setVars(extractVars(req.getContent()));
        t.setReviewStatus(TemplateEntity.REVIEW_DRAFT);
        t.setCreatedAt(Instant.now());
        templateMapper.insert(t);
        return t;
    }

    public TemplateEntity update(Long tenantId, Long id, TemplateWriteRequest req) {
        TemplateEntity t = get(tenantId, id);
        if (!TemplateEntity.REVIEW_DRAFT.equals(t.getReviewStatus()) && !TemplateEntity.REVIEW_REJECTED.equals(t.getReviewStatus())) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "仅 DRAFT|REJECTED 可编辑（当前 " + t.getReviewStatus() + "）");
        }
        validate(req);
        t.setChannel(req.getChannel());
        t.setTitle(req.getTitle());
        t.setContent(req.getContent());
        t.setVars(extractVars(req.getContent()));
        templateMapper.updateById(t);
        return t;
    }

    public TemplateEntity submit(Long tenantId, Long id) {
        TemplateEntity t = get(tenantId, id);
        if (!TemplateEntity.REVIEW_DRAFT.equals(t.getReviewStatus()) && !TemplateEntity.REVIEW_REJECTED.equals(t.getReviewStatus())) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "仅 DRAFT|REJECTED 可提交审核（当前 " + t.getReviewStatus() + "）");
        }
        t.setReviewStatus(TemplateEntity.REVIEW_PENDING);
        templateMapper.updateById(t);
        return t;
    }

    /** 审核操作：仅 REVIEWER 角色。 */
    public TemplateEntity review(Long tenantId, Long id, String role, boolean approve) {
        if (!Roles.REVIEWER.equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "审核需要 REVIEWER 角色");
        }
        TemplateEntity t = get(tenantId, id);
        if (!TemplateEntity.REVIEW_PENDING.equals(t.getReviewStatus())) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "仅 PENDING 可审核（当前 " + t.getReviewStatus() + "）");
        }
        t.setReviewStatus(approve ? TemplateEntity.REVIEW_APPROVED : TemplateEntity.REVIEW_REJECTED);
        templateMapper.updateById(t);
        return t;
    }

    public void delete(Long tenantId, Long id) {
        get(tenantId, id);
        Long refs = campaignMapper.selectCount(new QueryWrapper<CampaignEntity>()
                .eq(CampaignEntity.COL_TENANT_ID, tenantId).eq(CampaignEntity.COL_TEMPLATE_ID, id));
        if (refs != null && refs > 0) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "模板被活动引用为主模板，不可删除");
        }
        String json = "[{\"template_id\": " + id + "}]";
        Long routed = campaignMapper.selectCount(new QueryWrapper<CampaignEntity>()
                .eq(CampaignEntity.COL_TENANT_ID, tenantId)
                .apply("template_routing @> {0}::jsonb", json));
        if (routed != null && routed > 0) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "模板被活动规则路由引用，不可删除");
        }
        templateMapper.deleteById(id);
    }

    private void validate(TemplateWriteRequest req) {
        if (!CHANNELS.contains(req.getChannel())) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "未知通道（应为 sms|email|wechat|push|console）: " + req.getChannel());
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "模板内容不能为空");
        }
    }

    /** content 中 {{var}} 提取，去重保持出现顺序。 */
    static List<String> extractVars(String content) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = VAR.matcher(content == null ? "" : content);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return List.copyOf(out);
    }
}