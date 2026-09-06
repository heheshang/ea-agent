package com.eaagent.app.web;

import com.eaagent.agent.service.ApprovalService;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话审批门控（建议模式）：待批列表（GET）与决策（POST）。
 * 身份取自 TenantContext（登录态 JWT）；审批决策需 REVIEWER 及以上。
 */
@RestController
@RequestMapping("/api/agent/approvals")
public class AgentApprovalController {

    private final ApprovalService approvalService;

    public AgentApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> pending(
            @RequestParam(defaultValue = "pending") String status) {
        // v1 仅支持 pending 查询；后续可扩展已决策历史
        if (!"pending".equals(status)) {
            throw new IllegalArgumentException("暂仅支持 status=pending");
        }
        return Result.ok(approvalService.pending(TenantContext.requiredTenantId()));
    }

    @PostMapping("/{id}/decision")
    public Result<Map<String, Object>> decide(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return Result.ok(approvalService.decide(id, approved,
                TenantContext.userId(), TenantContext.role()));
    }
}