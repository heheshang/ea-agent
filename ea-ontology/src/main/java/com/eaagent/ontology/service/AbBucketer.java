package com.eaagent.ontology.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * AB 分桶（3.5.1）：bucket = SHA256(tenantId|campaignId|customerId) % 100；
 * ab_variants 按序对应 bucket 区间 [0,s1) [s1,s2) ...；≥ 总占比 → CONTROL。
 * 确定性（不随请求/时间漂移），哈希盐含租户+活动两维。
 */
@Service
public class AbBucketer {
    private static final String HEX = "0123456789abcdef";

    public int bucket(Long tenantId, Long campaignId, Long customerId) {
        String input = tenantId + "|" + campaignId + "|" + customerId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            // 取前 8 字节 → 无符号 64 位 → % 100
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFF);
            }
            return (int) Math.floorMod(value, 100);
        } catch (Exception e) {
            throw new IllegalStateException("ab bucketing failed", e);
        }
    }

    /** 变体区间判定：返回变体索引（0-based）；-1 = CONTROL。 */
    public int variant(int bucket, java.util.List<java.util.Map<String, Object>> variants, int totalPercent) {
        int acc = 0;
        for (int i = 0; i < variants.size(); i++) {
            acc += percentOf(variants.get(i));
            if (bucket < acc) {
                return i;
            }
        }
        return -1; // bucket ≥ 总占比 → CONTROL（也覆盖 variants 为空）
    }

    private int percentOf(java.util.Map<String, Object> v) {
        Object p = v.get("percent");
        if (p instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }
}