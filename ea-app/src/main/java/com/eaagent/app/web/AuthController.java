package com.eaagent.app.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.api.dto.LoginRequest;
import com.eaagent.api.dto.LoginResponse;
import com.eaagent.app.security.JwtService;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Result;
import com.eaagent.ontology.mapper.TenantUserMapper;
import com.eaagent.ontology.model.TenantUserEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录（7.1 POST /api/auth/login，白名单）：tenant_user 校验 → JWT（sub/tenantId/role）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TenantUserMapper tenantUserMapper;
    private final JwtService jwtService;
    private final PasswordEncoder encoder;

    public AuthController(TenantUserMapper tenantUserMapper, JwtService jwtService, PasswordEncoder encoder) {
        this.tenantUserMapper = tenantUserMapper;
        this.jwtService = jwtService;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest req) {
        TenantUserEntity user = tenantUserMapper.selectOne(new QueryWrapper<TenantUserEntity>()
                .eq(TenantUserEntity.COL_LOGIN_NAME, req.getLoginName())
                .eq(TenantUserEntity.COL_STATUS, TenantUserEntity.STATUS_ACTIVE)
                .last("LIMIT 1"));
        if (user == null || !encoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "用户名或密码错误");
        }
        String token = jwtService.createToken(user.getId(), user.getTenantId(), user.getRole());
        LoginResponse resp = new LoginResponse(
                token, user.getTenantId(), user.getId(), user.getName(), user.getRole());
        return Result.ok(resp);
    }
}