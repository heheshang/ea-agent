package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 审批决策请求（4.4：POST /api/agent/runs/{id}/approval）。 */
@Data
public class ApprovalRequest {
    @NotBlank
    private String decision;   // APPROVE | REJECT
    private Long actorId;      // 默认当前用户
}