package com.eaagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 登录响应：JWT access token（2h）+ 身份信息（前端 tenant store 用）。 */
@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private Long tenantId;
    private Long userId;
    private String name;
    private String role;
}