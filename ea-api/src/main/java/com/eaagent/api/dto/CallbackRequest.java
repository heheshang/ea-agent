package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通道回执回调请求（6.3：POST /api/channels/{type}/callback；HMAC 验签头 X-Signature）。
 * 状态：SENT → DELIVERED / BOUNCED / FAILED / UNSUBSCRIBED。
 */
@Data
public class CallbackRequest {
    @NotBlank
    private String messageId;
    @NotBlank
    private String status;
    private String error;
    private Long timestamp;   // 回调时间（参与验签，防重放）
}