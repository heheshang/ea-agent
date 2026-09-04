package com.eaagent.app.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发/解析（7.2）：claims = sub(userId)/tenantId/role/jti；access 2h。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlMs;

    public JwtService(@Value("${ea.security.jwt-secret}") String secret,
                      @Value("${ea.security.jwt-access-ttl-ms}") long accessTtlMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMs = accessTtlMs;
    }

    public String createToken(Long userId, Long tenantId, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .claim("role", role)
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessTtlMs))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名/过期；失败抛运行时异常（调用方按未认证处理）。 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}