package com.eaagent.ontology.service;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.TemplateEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模板路由（campaign.template_routing jsonb）：
 * 规则条目 {@code {"event_type":"order_placed","conditions":[{"attr":"new_customer","op":"eq","value":true}],"template_id":2}}。
 * 顺序匹配、首条命中；event_type 非空要求触发事件相等（无事件触发视为整条不匹配）；
 * conditions 全部满足（AND）；attr 取值优先级事件 payload → 客户 attributes → 客户实体字段；
 * 未命中回退 campaign.template_id。选中模板必须 APPROVED（审核门控，未过审抛 E-13002）。
 */
@Service
public class TemplateRoutingService {

    public static final String OPS = "eq|neq|contains|is_set";
    private static final Set<String> OP_SET = Set.of("eq", "neq", "contains", "is_set");

    private final TemplateMapper templateMapper;

    public TemplateRoutingService(TemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    /** 校验路由配置（保存路径调用）：template_id 租户内存在、条件 op 合法、attr 非空。 */
    public void validate(Long tenantId, List<Map<String, Object>> routes) {
        if (routes == null || routes.isEmpty()) {
            return;
        }
        for (Map<String, Object> r : routes) {
            Object tplId = r == null ? null : r.get("template_id");
            if (tplId == null) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "template_routing 条目缺少 template_id");
            }
            TemplateEntity t = templateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TemplateEntity>()
                    .eq(TemplateEntity.COL_TENANT_ID, tenantId)
                    .eq(TemplateEntity.COL_ID, toLong(tplId)));
            if (t == null) {
                throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "路由模板不存在: " + tplId);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conds = r.get("conditions") == null ? List.of() : (List<Map<String, Object>>) r.get("conditions");
            for (Map<String, Object> cd : conds) {
                if (cd == null || cd.get("attr") == null || String.valueOf(cd.get("attr")).isBlank()) {
                    throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "路由条件缺少 attr");
                }
                String op = String.valueOf(cd.get("op"));
                if (!OP_SET.contains(op)) {
                    throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "条件 op 应为 " + OPS + ": " + op);
                }
            }
        }
    }

    /**
     * 按触发上下文解析实际发送模板。cache 复用模板查询（人群内同一模板只查一次）；
     * 事件 payload 仅事件驱动触发传入（手动 trigger 为 null）。
     */
    public TemplateEntity resolve(CampaignEntity campaign, String eventType, Map<String, Object> eventPayload,
                                  CustomerEntity customer, Map<Long, TemplateEntity> cache) {
        List<Map<String, Object>> routes = campaign.getTemplateRouting();
        if (routes != null) {
            for (Map<String, Object> r : routes) {
                if (r == null) {
                    continue;
                }
                Object tplId = r.get("template_id");
                if (tplId == null || !eventMatches(r.get("event_type"), eventType)) {
                    continue;
                }
                if (!conditionsMatch(r.get("conditions"), eventPayload, customer)) {
                    continue;
                }
                return requireApproved(campaign.getTenantId(), toLong(tplId), cache);
            }
        }
        return requireApproved(campaign.getTenantId(), campaign.getTemplateId(), cache);
    }

    private boolean eventMatches(Object ruleEvent, String actualEvent) {
        if (ruleEvent == null || String.valueOf(ruleEvent).isBlank()) {
            return true; // 不约束事件
        }
        return actualEvent != null && String.valueOf(ruleEvent).equals(actualEvent);
    }

    @SuppressWarnings("unchecked")
    private boolean conditionsMatch(Object rawConds, Map<String, Object> eventPayload, CustomerEntity customer) {
        if (rawConds == null) {
            return true;
        }
        List<Map<String, Object>> conds = (List<Map<String, Object>>) rawConds;
        for (Map<String, Object> cd : conds) {
            if (cd == null) {
                continue;
            }
            String attr = String.valueOf(cd.get("attr"));
            String op = String.valueOf(cd.get("op"));
            Object expected = cd.get("value");
            Object actual = lookup(attr, eventPayload, customer);
            boolean hit = switch (op) {
                case "eq" -> actual != null && looseEquals(actual, expected);
                case "neq" -> actual == null || !looseEquals(actual, expected);
                case "contains" -> contains(actual, expected);
                case "is_set" -> actual != null;
                default -> false;
            };
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private Object lookup(String attr, Map<String, Object> eventPayload, CustomerEntity customer) {
        if (eventPayload != null && eventPayload.containsKey(attr)) {
            return eventPayload.get(attr);
        }
        if (customer == null) {
            return null;
        }
        if (customer.getAttributes() != null && customer.getAttributes().containsKey(attr)) {
            return customer.getAttributes().get(attr);
        }
        return switch (attr) {
            case "external_id" -> customer.getExternalId();
            case "phone" -> customer.getPhone();
            case "email" -> customer.getEmail();
            case "wechat_openid" -> customer.getWechatOpenid();
            case "status" -> customer.getStatus();
            case "tags" -> customer.getTags();
            default -> null;
        };
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a instanceof Number || b instanceof Number) {
            return String.valueOf(a).equals(String.valueOf(b));
        }
        return a.equals(b);
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual instanceof List<?> l) {
            return l.stream().anyMatch(x -> looseEquals(x, expected));
        }
        return String.valueOf(actual).contains(String.valueOf(expected));
    }

    private TemplateEntity requireApproved(Long tenantId, Long templateId, Map<Long, TemplateEntity> cache) {
        TemplateEntity t = cache.get(templateId);
        if (t == null) {
            t = templateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TemplateEntity>()
                    .eq(TemplateEntity.COL_TENANT_ID, tenantId)
                    .eq(TemplateEntity.COL_ID, templateId));
            cache.put(templateId, t);
        }
        if (t == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "模板不存在: " + templateId);
        }
        if (!TemplateEntity.REVIEW_APPROVED.equals(t.getReviewStatus())) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                    "模板未通过审核（review_status=" + t.getReviewStatus() + "）: " + templateId);
        }
        return t;
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(o));
    }
}