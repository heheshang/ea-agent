package com.eaagent.app.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.api.dto.TemplateWriteRequest;
import com.eaagent.api.dto.TemplateReviewRequest;
import com.eaagent.common.BizException;
import com.eaagent.common.Channels;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Roles;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.mapper.TemplateReviewMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.model.TemplateReviewEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板管理（/api/templates）：租户维度 CRUD + 审核流（DRAFT|REJECTED →submit→ PENDING
 * →人工终审→ APPROVED|REJECTED）。vars 由 content 的 {{占位符}} 自动提取；
 * 编辑仅限 DRAFT|REJECTED；删除须无活动引用（主模板或路由模板）。
 */
@Service
public class TemplateService {

    private static final Set<String> CHANNELS = Channels.ALL_SET;
    private static final Set<String> REVIEW_DECISIONS = Set.of(TemplateReviewEntity.DECISION_COMMENT,
            TemplateReviewEntity.DECISION_APPROVE, TemplateReviewEntity.DECISION_REJECT);
    private static final Pattern VAR = Pattern.compile("\\{\\{([^{}]+)}}");

    private final TemplateMapper templateMapper;
    private final CampaignMapper campaignMapper;
    private final TemplateReviewMapper reviewMapper;
    private final AgentChatService chatService;

    public TemplateService(TemplateMapper templateMapper, CampaignMapper campaignMapper,
                           TemplateReviewMapper reviewMapper, AgentChatService chatService) {
        this.templateMapper = templateMapper;
        this.campaignMapper = campaignMapper;
        this.reviewMapper = reviewMapper;
        this.chatService = chatService;
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
        QueryWrapper<TemplateEntity> expected = expectedVersion(t);
        t.setChannel(req.getChannel());
        t.setTitle(req.getTitle());
        t.setContent(req.getContent());
        t.setVars(extractVars(req.getContent()));
        t.setVersion(t.getVersion() + 1);
        if (templateMapper.update(t, expected) != 1) {
            throw staleVersion();
        }
        return t;
    }

    public TemplateEntity submit(Long tenantId, Long id) {
        TemplateEntity t = get(tenantId, id);
        if (!TemplateEntity.REVIEW_DRAFT.equals(t.getReviewStatus()) && !TemplateEntity.REVIEW_REJECTED.equals(t.getReviewStatus())) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "仅 DRAFT|REJECTED 可提交审核（当前 " + t.getReviewStatus() + "）");
        }
        QueryWrapper<TemplateEntity> expected = expectedVersion(t);
        t.setReviewStatus(TemplateEntity.REVIEW_PENDING);
        t.setVersion(t.getVersion() + 1);
        if (templateMapper.update(t, expected) != 1) {
            throw staleVersion();
        }
        return t;
    }

    /** 同租户可读的模板审核历史，不查询或返回任何聊天消息、描述、运行记录。 */
    public List<TemplateReviewEntity> listReviews(Long tenantId, Long id) {
        get(tenantId, id);
        return reviewMapper.selectList(new QueryWrapper<TemplateReviewEntity>()
                .eq(TemplateReviewEntity.COL_TENANT_ID, tenantId)
                .eq(TemplateReviewEntity.COL_TEMPLATE_ID, id)
                .orderByAsc(TemplateReviewEntity.COL_CREATED_AT, TemplateReviewEntity.COL_ID));
    }

    /** 锁定原文后记录批注/决定；最终状态与审计记录必须一起提交或回滚。 */
    @Transactional
    public TemplateReviewEntity addReview(Long tenantId, Long id, Long userId, String role,
                                          TemplateReviewRequest req) {
        validateReview(req);
        boolean finalDecision = !TemplateReviewEntity.DECISION_COMMENT.equals(req.getDecision());
        if (finalDecision && !Roles.REVIEWER.equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "审核需要 REVIEWER 角色");
        }
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "审核记录需要登录身份");
        }
        if (req.getChatId() != null) {
            chatService.getChat(tenantId, userId, req.getChatId());
        }
        TemplateEntity t = lockTemplate(tenantId, id);
        if (t.getVersion() != req.getVersion()) {
            throw staleVersion();
        }
        if (finalDecision && !TemplateEntity.REVIEW_PENDING.equals(t.getReviewStatus())) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED,
                    "仅 PENDING 可审核（当前 " + t.getReviewStatus() + "）");
        }

        TemplateReviewEntity review = new TemplateReviewEntity();
        review.setTenantId(tenantId);
        review.setTemplateId(id);
        review.setTemplateVersion(t.getVersion());
        review.setChatId(req.getChatId());
        review.setUserId(userId);
        review.setDecision(req.getDecision());
        review.setComment(req.getComment().trim());
        review.setTitle(t.getTitle());
        review.setContent(t.getContent());
        review.setCreatedAt(Instant.now());

        if (finalDecision) {
            QueryWrapper<TemplateEntity> expected = expectedVersion(t);
            t.setReviewStatus(TemplateReviewEntity.DECISION_APPROVE.equals(req.getDecision())
                    ? TemplateEntity.REVIEW_APPROVED : TemplateEntity.REVIEW_REJECTED);
            t.setVersion(t.getVersion() + 1);
            if (templateMapper.update(t, expected) != 1) {
                throw staleVersion();
            }
        }
        if (reviewMapper.insert(review) != 1) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED, "审核记录保存失败");
        }
        return review;
    }

    @Transactional
    public void delete(Long tenantId, Long id) {
        lockTemplate(tenantId, id);
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
        // review 外键级联删除，与 addReview 的模板行锁互斥，不留下悬挂记录。
        templateMapper.delete(new QueryWrapper<TemplateEntity>()
                .eq(TemplateEntity.COL_TENANT_ID, tenantId).eq(TemplateEntity.COL_ID, id));
    }

    private TemplateEntity lockTemplate(Long tenantId, Long id) {
        TemplateEntity t = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                .eq(TemplateEntity.COL_TENANT_ID, tenantId).eq(TemplateEntity.COL_ID, id)
                .last("FOR UPDATE"));
        if (t == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "模板不存在: " + id);
        }
        return t;
    }

    private QueryWrapper<TemplateEntity> expectedVersion(TemplateEntity t) {
        return new QueryWrapper<TemplateEntity>()
                .eq(TemplateEntity.COL_TENANT_ID, t.getTenantId())
                .eq(TemplateEntity.COL_ID, t.getId())
                .eq(TemplateEntity.COL_VERSION, t.getVersion())
                .eq(TemplateEntity.COL_REVIEW_STATUS, t.getReviewStatus());
    }

    private BizException staleVersion() {
        return new BizException(ErrorCode.STATE_NOT_ALLOWED, "模板版本已变更，请刷新后重试");
    }

    private void validateReview(TemplateReviewRequest req) {
        if (req == null || req.getVersion() == null || req.getVersion() < 0
                || req.getDecision() == null
                || !REVIEW_DECISIONS.contains(req.getDecision())
                || req.getComment() == null || req.getComment().length() > 2000
                || (req.getChatId() != null && req.getChatId() <= 0)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板审核参数无效");
        }
        if (!TemplateReviewEntity.DECISION_APPROVE.equals(req.getDecision()) && req.getComment().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "批注或驳回必须填写原因");
        }
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