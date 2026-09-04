package com.eaagent.common;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis 幂等闸（详细设计 2.2 / 8.4）：双闸之一 —— SETNX 抢占；
 * 第二闸 = 落库唯一约束（delivery(tenant_id, request_id) / event(tenant_id, dedup_key)）。
 * 键命名空间：ea:idem:{tenant}:{requestId}（对齐数据流 7.1）。
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {
    public static final String KEY_PREFIX = "ea:idem:";

    private final StringRedisTemplate redis;

    /** 尝试抢占幂等键；返回 true 表示首次（可继续执行），false 表示重放。 */
    public boolean tryAcquire(Long tenantId, String requestId, Duration ttl) {
        String key = key(tenantId, requestId);
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(Long tenantId, String requestId) {
        redis.delete(key(tenantId, requestId));
    }

    public String key(Long tenantId, String requestId) {
        return KEY_PREFIX + tenantId + ":" + requestId;
    }
}