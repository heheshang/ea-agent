package com.eaagent.agent.service;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionRegistry;
import com.eaagent.ontology.action.ActionResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话 HITL 门控（建议模式，人在环内）：ApplyAction 在 suggest 模式下把写动作挂起到
 * ea:agent:approval:pending（Redis List，JSON entry），等待用户在聊天中确认。
 * 确认（approved=true）→ 以原请求身份 + 门控上下文（approval:{id}）执行动作，随后移除条目；
 * 取消（approved=false）→ 不执行，移除条目。门控一次性：确认/取消后条目即删，无状态留存。
 * 门控权限：仅限动作发起者本人确认自己挂起的动作。
 * entry: {id, tenant_id, user_id, role, session_id, action, args, created_at}
 */
@Component
public class ApprovalService {

    static final String PENDING_KEY = "ea:agent:approval:pending";

    private final StringRedisTemplate redis;
    private final ActionRegistry actionRegistry;

    public ApprovalService(StringRedisTemplate redis, ActionRegistry actionRegistry) {
        this.redis = redis;
        this.actionRegistry = actionRegistry;
    }

    /**
     * 门控确认：approved=true → 执行挂起动作并移除条目；false → 直接移除条目。
     * 返回 {approval_id, action, result?}。仅限动作发起者本人操作。
     * 执行失败（如 action 校验错）→ 条目保留，前端可重试或取消。
     */
    public Map<String, Object> decide(String approvalId, boolean approved, Long userId) {
        List<String> raw = redis.opsForList().range(PENDING_KEY, 0, -1);
        if (raw == null) {
            raw = List.of();
        }
        Map<String, Object> entry = null;
        String json = null;
        for (String s : raw) {
            Map<String, Object> e = JsonUtils.readMap(s);
            if (approvalId.equals(e.get("id"))) {
                entry = e;
                json = s;
                break;
            }
        }
        if (entry == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "门控项不存在或已处理: " + approvalId);
        }
        boolean owner = entry.get("user_id") != null
                && Long.valueOf(String.valueOf(entry.get("user_id"))).equals(userId);
        if (!owner) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "仅动作发起者可确认该门控项，当前用户: " + userId);
        }
        Map<String, Object> result = null;
        if (approved) {
            Long tenantId = Long.valueOf(String.valueOf(entry.get("tenant_id")));
            Long entryUserId = Long.valueOf(String.valueOf(entry.get("user_id")));
            String role = String.valueOf(entry.get("role"));
            String action = String.valueOf(entry.get("action"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = entry.get("args") instanceof Map
                    ? (Map<String, Object>) entry.get("args") : Map.of();
            ActionContext ctx = ActionContext.of(tenantId, entryUserId, role, "approval:" + approvalId);
            ActionResult r = actionRegistry.get(action).execute(ctx,
                    com.eaagent.ontology.action.ActionRequest.of(args));
            result = r.data() == null ? Map.of("ok", r.success()) : r.data();
        }
        // 门控一次性：确认/取消后移除条目，不留状态
        try {
            redis.opsForList().remove(PENDING_KEY, 0, json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.STATE_NOT_ALLOWED, "门控项移除失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approval_id", approvalId);
        out.put("action", entry.get("action"));
        if (result != null) {
            out.put("result", result);
        }
        return out;
    }
}