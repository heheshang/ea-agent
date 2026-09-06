package com.eaagent.ontology.action;

import com.eaagent.common.BizException;
import com.eaagent.common.Channels;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.common.Roles;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.model.TemplateEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * createTemplate（10.4 扩展）：agent 侧的模板创建动作 —— 编排链路里「模板未创建时先创建模板」。
 * 产物 review_status 直接 APPROVED：会话审批门控（auto 直接放行 / suggest 挂起人工审批）已在上层
 * ApplyAction 承担审核职责，模板本身不再进 DRAFT→PENDING 审核流（与 Web 传统 submit→approve 流程并存）。
 * content 的 {{占位符}} 自动提取为 vars，供路由条件与渲染使用。
 */
@Component
public class CreateTemplateAction extends AbstractAction {

    private static final Set<String> CHANNELS = Channels.ALL_SET;
    private static final Pattern VAR = Pattern.compile("\\{\\{([^{}]+)}}");

    private final TemplateMapper templateMapper;

    public CreateTemplateAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                                StringRedisTemplate redis, TemplateMapper templateMapper) {
        super(actionLogMapper, idempotencyService, redis);
        this.templateMapper = templateMapper;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("createTemplate")
                .description("创建触达模板（{{var}} 占位符自动提取；创建即 APPROVED 可直接发送；在编排/路由需要模板而模板未创建时先调用本动作）")
                .requiredArgs(List.of("title", "channel", "content"))
                .permissions(List.of(Roles.OPERATOR))
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        String channel = req.getString("channel");
        String content = req.getString("content");
        if (!CHANNELS.contains(channel)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "未知通道（应为 sms|email|wechat|push|console）: " + channel);
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "模板内容 content 不能为空");
        }
        TemplateEntity t = new TemplateEntity();
        t.setTenantId(ctx.tenantId());
        t.setChannel(channel);
        t.setTitle(req.getString("title"));
        t.setContent(content);
        t.setVars(extractVars(content));
        t.setReviewStatus(TemplateEntity.REVIEW_APPROVED);
        t.setCreatedAt(Instant.now());
        templateMapper.insert(t);

        Map<String, Object> out = new java.util.HashMap<>();
        out.put("template_id", t.getId());
        out.put("review_status", t.getReviewStatus());
        out.put("vars", t.getVars());
        return out;
    }

    /** content 中 {{var}} 提取，去重保持出现顺序（与 TemplateService 同规则）。 */
    static List<String> extractVars(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = VAR.matcher(content);
        while (m.find()) {
            vars.add(m.group(1).trim());
        }
        return List.copyOf(vars);
    }
}