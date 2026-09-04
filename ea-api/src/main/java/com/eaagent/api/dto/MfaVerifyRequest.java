package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** MFA 挑战校验请求（9.1 步骤 2）。 */
@Data
public class MfaVerifyRequest {
    @NotBlank
    private String challengeId;
    @NotBlank
    private String code;
}