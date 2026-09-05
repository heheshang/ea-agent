package com.eaagent.ontology.bus;

import com.eaagent.ontology.model.EventEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EA-Bus 发布（5.3）：event 持久化后写入 Redis Stream「ea:events」（XADD，无 block）。
 * 失败由上层补偿（事件已入库不丢；总线补发后续迭代）。
 */
@Service
@RequiredArgsConstructor
public class StreamPublisher {
    public static final String STREAM = "ea:events";

    private final StringRedisTemplate redis;

    public String publish(EventEntity event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event_id", String.valueOf(event.getId()));
        fields.put("tenant_id", String.valueOf(event.getTenantId()));
        fields.put("customer_id", String.valueOf(event.getCustomerId()));
        fields.put("event_type", event.getEventType());
        // payload 业务字段随流透传：模板路由条件与占位符渲染依赖（事件→属性取值链）
        if (event.getPayload() != null && !event.getPayload().isEmpty()) {
            fields.put("event_payload", com.eaagent.common.JsonUtils.write(event.getPayload()));
        }
        String messageId = redis.opsForStream().add(
                StreamRecords.newRecord().ofObject(fields).withStreamKey(STREAM)).getValue();
        return messageId;
    }
}