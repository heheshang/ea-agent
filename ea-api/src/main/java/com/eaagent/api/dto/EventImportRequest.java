package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 事件导入请求（= importEvents Action，10.3：幂等 (tenant_id, dedup_key)）。 */
@Data
public class EventImportRequest {
    private Long customerId;              // 与 customerExternalId 二选一
    private String customerExternalId;
    @NotBlank
    private String eventType;
    private java.util.Map<String, Object> payload;
    @NotBlank
    private String dedupKey;              // 调用方幂等键（同事件重放同值）
}