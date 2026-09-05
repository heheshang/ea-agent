package com.eaagent.app.security;

import com.eaagent.common.ErrorCode;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.mapper.TenantMapper;
import com.eaagent.ontology.model.TenantEntity;
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
 * 租户上下文过滤器（9.2）：X-Tenant-Id → TenantContext；租户状态校验（E-11003）；
 * JWT（Authorization: Bearer）解析身份；白名单端点不强制租户头（SSE 由服务端恢复）。
 */
@Component
public class TenantFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

    private final TenantMapper tenantMapper;
    private final JwtService jwtService;

    public TenantFilter(TenantMapper tenantMapper, JwtService jwtService) {
        this.tenantMapper = tenantMapper;
        this.jwtService = jwtService;
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

        Long userId = null;
        String role = null;
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(auth.substring(7));
                Long jwtTenant = claims.get("tenantId", Long.class);
                if (jwtTenant != null && jwtTenant != tenantId) {
                    reject(response, ErrorCode.TENANT_MISMATCH, "token tenant mismatch");
                    return;
                }
                userId = Long.valueOf(claims.getSubject());
                role = claims.get("role", String.class);
            } catch (Exception e) {
                reject(response, ErrorCode.UNAUTHENTICATED, "invalid token");
                return;
            }
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
        if (path.startsWith("/actuator") || path.equals("/error")) {
            return true;
        }
        if ("POST".equals(method) && path.equals("/api/auth/login")) {
            return true;
        }
        if ("GET".equals(method) && path.equals("/api/agent/chat")) {
            return true; // SSE：session 标识经 query，租户由 run 恢复（7.4）
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