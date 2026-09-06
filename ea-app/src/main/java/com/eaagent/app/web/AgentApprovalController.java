package com.eaagent.app.web;

import com.eaagent.agent.service.ApprovalService;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话 HITL 门控（建议模式，人在环内）：写动作在 suggest 模式下挂起，
 * 聊天中由动作发起者本人确认（执行）或取消。门控一次性：确认/取消后即移除。
 */
@RestController
@RequestMapping("/api/agent/approvals")
public class AgentApprovalController {

    private final ApprovalService approvalService;

    public AgentApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/{id}/decision")
    public Result<Map<String, Object>> decide(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return Result.ok(approvalService.decide(id, approved, TenantContext.userId()));
    }
}