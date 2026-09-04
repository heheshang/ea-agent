package com.eaagent.ontology.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 冷却窗（10.3 步骤 5）：SETNX ea:cd:{tenant}:{campaign}:{customerId}，TTL=触发器 cooldown。
 * 命中冷却窗 → 该次跳过发送（XACK 正常消费，不重试）。
 */
@Service
@RequiredArgsConstructor
public class CooldownService {
    public static final String KEY_PREFIX = "ea:cd:";

    private static final Logger log = LoggerFactory.getLogger(CooldownService.class);

    private final StringRedisTemplate redis;

    /** 尝试进入冷却窗；返回 true = 窗口未占用（可发送），false = 冷却中（跳过）。 */
    public boolean tryEnter(Long tenantId, Long campaignId, Long customerId, Duration ttl) {
        String key = KEY_PREFIX + tenantId + ":" + campaignId + ":" + customerId;
        if (ttl == null) {
            log.warn("cooldown skipped tenantId={} campaignId={} customerId={} reason=non-positive ttl 0s",
                    tenantId, campaignId, customerId);
            return true;
        }
        long seconds = ttl.toSeconds();
        if (seconds <= 0) {
            if (ttl.isZero() || ttl.isNegative()) {
                log.warn("cooldown skipped tenantId={} campaignId={} customerId={} reason=non-positive ttl {}s",
                        tenantId, campaignId, customerId, seconds);
                return true;
            }
            // 0 < ttl < 1s（如 PT0.5S）：按 1 秒设置，保留窗口意图且避免 invalid expire time
            seconds = 1;
        }
        Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(seconds));
        return Boolean.TRUE.equals(ok);
    }
}