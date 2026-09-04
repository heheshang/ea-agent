package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 主动退订登记请求（9.5：customer.write；customer_key 哈希，任一租户登记即全平台生效）。 */
@Data
public class UnsubscribeRequest {
    @NotBlank
    private Long customerId;
    @NotBlank
    private String channel;
    private String reason;
}