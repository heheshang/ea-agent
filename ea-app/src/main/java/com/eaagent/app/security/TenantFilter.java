package com.eaagent.app.security;

import com.eaagent.common.ErrorCode;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.mapper.TenantMapper;
import com.eaagent.ontology.model.TenantEntity;
import com.eaagent.ontology.mapper.TenantUserMapper;
import com.eaagent.ontology.model.TenantUserEntity;
import com.eaagent.common.Roles;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 租户与身份过滤器：除登录、回执和运维端点外，请求必须同时携带有效 JWT 与
 * {@code X-Tenant-Id}；两者租户必须一致，校验通过后才建立 {@link TenantContext}。
 */
@Component
public class TenantFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    private final TenantMapper tenantMapper;
    private final JwtService jwtService;
    private final TenantUserMapper userMapper;

    public TenantFilter(TenantMapper tenantMapper, JwtService jwtService, TenantUserMapper userMapper) {
        this.tenantMapper = tenantMapper;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isWhitelisted(request)) {
            chain.doFilter(request, response);
            return;
        }
        String tenantHeader = request.getHeader("X-Tenant-Id");
        if (tenantHeader == null || tenantHeader.isBlank()) {
            reject(response, ErrorCode.TENANT_CONTEXT_MISSING, "missing X-Tenant-Id header");
            return;
        }
        long tenantId;
        try {
            tenantId = Long.parseLong(tenantHeader);
        } catch (NumberFormatException e) {
            reject(response, ErrorCode.TENANT_CONTEXT_MISSING, "invalid X-Tenant-Id header");
            return;
        }

        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || !TenantEntity.STATUS_ACTIVE.equals(tenant.getStatus())) {
            reject(response, ErrorCode.TENANT_DISABLED, "tenant disabled or not found");
            return;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || auth.length() == 7) {
            reject(response, ErrorCode.UNAUTHENTICATED, "missing bearer token");
            return;
        }
        Long userId;
        String role;
        try {
            Claims claims = jwtService.parse(auth.substring(7));
            Long jwtTenant = claims.get("tenantId", Long.class);
            if (jwtTenant == null || jwtTenant.longValue() != tenantId) {
                reject(response, ErrorCode.TENANT_MISMATCH, "token tenant mismatch");
                return;
            }
            userId = Long.valueOf(claims.getSubject());
            role = claims.get("role", String.class);
            if (userId <= 0 || role == null || role.isBlank()) {
                reject(response, ErrorCode.UNAUTHENTICATED, "invalid token claims");
                return;
            }
        } catch (Exception e) {
            reject(response, ErrorCode.UNAUTHENTICATED, "invalid token");
            return;
        }
        TenantUserEntity user = userMapper.selectById(userId);
        if (user == null || !Long.valueOf(tenantId).equals(user.getTenantId())
                || !TenantUserEntity.STATUS_ACTIVE.equals(user.getStatus())
                || !role.equals(user.getRole()) || !Roles.ROLE_LEVEL.containsKey(role)) {
            reject(response, ErrorCode.UNAUTHENTICATED, "user disabled or token identity outdated");
            return;
        }
        try {
            TenantContext.setIdentity(tenantId, userId, role);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private boolean isWhitelisted(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/") || path.equals("/error")) {
            return true;
        }
        if ("POST".equals(method) && path.equals("/api/auth/login")) {
            return true;
        }
        if ("POST".equals(method)
                && path.matches("/api/channels/(sms|email|wechat|push|console)/callback")) {
            return true; // 回执验签含租户派生密钥，不依赖请求头
        }
        return false;
    }

    private void reject(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtils.write(Result.error(code, message)));
    }
}