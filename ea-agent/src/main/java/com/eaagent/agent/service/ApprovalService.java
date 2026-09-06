package com.eaagent.agent.service;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.common.Roles;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionRegistry;
import com.eaagent.ontology.action.ActionResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话审批门控（建议模式）：ApplyAction 在 suggest 模式下把写动作挂起到
 * ea:agent:approval:pending（Redis List，JSON entry），本服务提供待批列表与决策。
 * 批准：以原请求身份 + 审批上下文（approval:{id}）执行动作，entry 更新为 APPROVED 并回写；
 * 拒绝：不执行，entry 更新为 REJECTED。审批人需 ROLE_LEVEL ≥ REVIEWER（2）。
 * entry: {id, tenant_id, user_id, role, session_id, action, args, status, created_at, reviewer_id?, reviewer_role?, decided_at?, result?}
 */
@Component
public class ApprovalService {

    static final String PENDING_KEY = "ea:agent:approval:pending";
    private static final int MAX_ENTRIES = 200;

    private final StringRedisTemplate redis;
    private final ActionRegistry actionRegistry;

    public ApprovalService(StringRedisTemplate redis, ActionRegistry actionRegistry) {
        this.redis = redis;
        this.actionRegistry = actionRegistry;
    }

    /** 待批列表：过滤租户 + PENDING，按创建时间升序（LRANGE 顺序）。 */
    public List<Map<String, Object>> pending(Long tenantId) {
        List<String> raw = redis.opsForList().range(PENDING_KEY, 0, -1);
        if (raw == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String s : raw) {
            Map<String, Object> e = JsonUtils.readMap(s);
            if (Long.valueOf(String.valueOf(e.get("tenant_id"))).equals(tenantId)
                    && "PENDING".equals(e.get("status"))) {
                out.add(e);
            }
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return out;
    }

    /**
     * 决策：批准（true）→ 执行挂起动作并回写 APPROVED；拒绝 → 回写 REJECTED。
     * 返回 {approval_id, status, action?, result?}。
     */
    public Map<String, Object> decide(String approvalId, boolean approved,
                                      Long reviewerId, String reviewerRole) {
        if (reviewerLevel(reviewerRole) < Roles.ROLE_LEVEL.get(Roles.REVIEWER)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "审批决策需要 REVIEWER 及以上角色，当前: " + reviewerRole);
        }
        List<String> raw = redis.opsForList().range(PENDING_KEY, 0, -1);
        if (raw == null) {
            raw = List.of();
        }
        Map<String, Object> entry = null;
        String json = null;
        for (String s : raw) {
            Map<String, Object> e = JsonUtils.readMap(s);
            if (approvalId.equals(e.get("id")) && "PENDING".equals(e.get("status"))) {
                entry = e;
                json = s;
                break;
            }
        }
        if (entry == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "待批项不存在或已决策: " + approvalId);
        }
        Map<String, Object> result = null;
        if (approved) {
            Long tenantId = Long.valueOf(String.valueOf(entry.get("tenant_id")));
            Long userId = Long.valueOf(String.valueOf(entry.get("user_id")));
            String role = String.valueOf(entry.get("role"));
            String action = String.valueOf(entry.get("action"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = entry.get("args") instanceof Map
                    ? (Map<String, Object>) entry.get("args") : Map.of();
            ActionContext ctx = ActionContext.of(tenantId, userId, role, "approval:" + approvalId);
            ActionResult r = actionRegistry.get(action).execute(ctx,
                    com.eaagent.ontology.action.ActionRequest.of(args));
            result = r.data() == null ? Map.of("ok", r.success()) : r.data();
        }
        entry.put("status", approved ? "APPROVED" : "REJECTED");
        entry.put("reviewer_id", reviewerId);
        entry.put("reviewer_role", reviewerRole);
        entry.put("decided_at", Instant.now().toString());
        if (result != null) {
            entry.put("result", result);
        }
        // 原位更新：LREM 旧 JSON（精确匹配）后 rightPush 新 entry
        try {
            redis.opsForList().remove(PENDING_KEY, 0, json);
            redis.opsForList().rightPush(PENDING_KEY, JsonUtils.write(entry));
        } catch (Exception e) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED, "审批状态回写失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approval_id", approvalId);
        out.put("status", entry.get("status"));
        out.put("action", entry.get("action"));
        if (result != null) {
            out.put("result", result);
        }
        return out;
    }

    /** 角色级别（未知角色 0）。 */
    private static int reviewerLevel(String role) {
        Integer lvl = Roles.ROLE_LEVEL.get(role);
        return lvl == null ? 0 : lvl;
    }
}