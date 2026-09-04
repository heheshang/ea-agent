package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求（9.1：bcrypt 认证，登录限速）。 */
@Data
public class LoginRequest {
    @NotBlank
    private String loginName;
    @NotBlank
    private String password;
}