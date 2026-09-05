package com.eaagent.ontology.action;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.common.Roles;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.AudienceMemberMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.AudienceMemberEntity;
import com.eaagent.ontology.service.AudienceResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * createAudience（修复「圈定新人群」路径缺失）：Agent 按 DSL 规则圈定目标人群
 * （name + rule，如 attributes.hobby == '跑步'）或导入静态成员（name + member_customer_ids）。
 * 创建即校验：DYNAMIC 规则非空且可解析（并预览成员数），对齐 audience 表
 * chk_audience_mode（DYNAMIC 必须有规则 / STATIC 规则为空，互斥）。
 */
@Component
public class CreateAudienceAction extends AbstractAction {

    private final AudienceMapper audienceMapper;
    private final AudienceMemberMapper audienceMemberMapper;
    private final AudienceResolver audienceResolver;

    public CreateAudienceAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                                StringRedisTemplate redis, AudienceMapper audienceMapper,
                                AudienceMemberMapper audienceMemberMapper, AudienceResolver audienceResolver) {
        super(actionLogMapper, idempotencyService, redis);
        this.audienceMapper = audienceMapper;
        this.audienceMemberMapper = audienceMemberMapper;
        this.audienceResolver = audienceResolver;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("createAudience")
                .description("圈定新人群：name + rule（DSL 规则人群，如 attributes.hobby == '跑步'）或 name + member_customer_ids（静态成员人群）；返回 audience_id 与 member_count，创建运营活动前用它圈定本次目标人群")
                .requiredArgs(List.of("name"))
                .permissions(List.of(Roles.OPERATOR))
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        String name = req.getString("name");
        String rule = req.getString("rule");
        List<Long> memberIds = extractCustomerIds(req.getList("member_customer_ids"));
        boolean dynamic = memberIds == null; // 未给成员 → 规则人群；给了（即使空列表）→ 静态人群需非空
        if (dynamic && (rule == null || rule.isBlank())) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "圈定人群需二选一：rule（DSL 规则，如 attributes.hobby == '跑步'）或 member_customer_ids（静态成员）");
        }
        if (!dynamic && memberIds.isEmpty()) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "静态人群 member_customer_ids 不能为空（未给成员且未给 rule 时按规则人群处理）");
        }

        AudienceEntity a = new AudienceEntity();
        a.setTenantId(ctx.tenantId());
        a.setName(name);
        a.setMode(dynamic ? "DYNAMIC" : "STATIC");
        a.setRule(dynamic ? rule : null);
        a.setOwnerId(ctx.userId());
        a.setStatus(AudienceEntity.STATUS_ACTIVE);
        a.setCreatedAt(Instant.now());

        // 创建即校验 + 预览：非法 rule（空/白名单越界/语法错）在落库前抛错，不产生脏人群
        int memberCount;
        if (dynamic) {
            memberCount = audienceResolver.resolve(ctx.tenantId(), a).size();
            audienceMapper.insert(a);
        } else {
            audienceMapper.insert(a);
            for (Long cid : memberIds) {
                AudienceMemberEntity m = new AudienceMemberEntity();
                m.setTenantId(ctx.tenantId());
                m.setAudienceId(a.getId());
                m.setCustomerId(cid);
                m.setCreatedAt(Instant.now());
                audienceMemberMapper.insert(m);
            }
            memberCount = memberIds.size();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("audience_id", a.getId());
        out.put("name", a.getName());
        out.put("mode", a.getMode());
        out.put("rule", a.getRule());
        out.put("member_count", memberCount);
        return out;
    }

    /**
     * member_customer_ids 解析：容忍 [{customer_id:1}, {id:1}] 或 [1, 2, 3] 两种形态；
     * 键缺失/非数字忽略；整个参数缺失返回 null（= 规则人群），给出空列表返回空列表（= 静态人群校验）。
     */
    private static List<Long> extractCustomerIds(Object raw) {
        if (raw == null) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                } else if (o instanceof Map<?, ?> m) {
                    Object cid = m.get("customer_id") != null ? m.get("customer_id") : m.get("id");
                    if (cid instanceof Number n) {
                        ids.add(n.longValue());
                    } else if (cid != null) {
                        ids.add(Long.valueOf(String.valueOf(cid)));
                    }
                } else if (o != null) {
                    ids.add(Long.valueOf(String.valueOf(o)));
                }
            }
        }
        return ids;
    }
}