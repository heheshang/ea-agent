package com.eaagent.agent.bus;

import com.eaagent.common.Actors;
import com.eaagent.common.JsonUtils;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionRegistry;
import com.eaagent.ontology.action.ActionRequest;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.model.CampaignEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EA-Bus 事件消费（详细设计 10.3）：XREADGROUP ea:consumer 轮询 ea:events，
 * 匹配 RUNNING 活动触发规则 → sendTouch → XACK；
 * 失败写 ea:events:dlq 后 XACK，事件不丢失（已落库，总线补偿后续迭代）。
 */
@Component
public class EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    public static final String STREAM = "ea:events";
    public static final String GROUP = "ea:consumer";
    public static final String DLQ = "ea:events:dlq";
    public static final String CONSUMER = "ea-ops";

    private final StringRedisTemplate redis;
    private final CampaignMapper campaignMapper;
    private final ActionRegistry actionRegistry;

    public EventConsumer(StringRedisTemplate redis, CampaignMapper campaignMapper, ActionRegistry actionRegistry) {
        this.redis = redis;
        this.campaignMapper = campaignMapper;
        this.actionRegistry = actionRegistry;
    }

    @PostConstruct
    public void initGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, GROUP);
            log.info("created stream group {} on {}", GROUP, STREAM);
        } catch (Exception e) {
            log.debug("stream group already exists: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${ea.bus.poll-ms:2000}")
    public void poll() {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redis.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        } catch (Exception e) {
            log.debug("stream read failed: {}", e.toString());
            return;
        }
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> r : records) {
            process(r);
        }
    }

    private void process(MapRecord<String, Object, Object> r) {
        Map<Object, Object> f = r.getValue();
        String recordId = r.getId().getValue();
        Long tenantId = Long.valueOf(String.valueOf(f.get("tenant_id")));
        String eventType = String.valueOf(f.get("event_type"));
        try {
            TenantContext.setIdentity(tenantId, null, Actors.SYSTEM);
            List<CampaignEntity> campaigns = campaignMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CampaignEntity>()
                            .eq(CampaignEntity.COL_TENANT_ID, tenantId)
                            .eq(CampaignEntity.COL_STATUS, CampaignEntity.STATUS_RUNNING));
            int matched = 0;
            for (CampaignEntity c : campaigns) {
                Map<String, Object> rule = c.getTriggerRule() == null ? Map.of() : c.getTriggerRule();
                if (eventType.equals(rule.get("event_type"))) {
                    matched++;
                    ActionContext ctx = ActionContext.of(tenantId, null, Actors.SYSTEM, "evt:" + recordId);
                    Map<String, Object> payload = toStringKeyMap(f);
                    String nested = payload.get("event_payload") == null ? null : String.valueOf(payload.get("event_payload"));
                    if (nested != null && !nested.isBlank()) {
                        try {
                            // 展开 payload 业务字段到顶层：模板路由条件/占位符渲染统一从上取值
                            payload.putAll(JsonUtils.readMap(nested));
                        } catch (Exception ignore) {
                            log.warn("event {} invalid event_payload json, skipped", recordId);
                        }
                    }
                    ActionRequest req = ActionRequest.of(Map.of(
                            "campaign_id", c.getId(),
                            "event_type", eventType,
                            "event_payload", payload));
                    actionRegistry.get("sendTouch").execute(ctx, req);
                }
            }
            log.info("event {} matched {} campaign(s)", recordId, matched);
            ack(recordId);
        } catch (Exception e) {
            log.error("event {} processing failed: {}", recordId, e.toString());
            toDlq(f, e.toString());
            ack(recordId);
        } finally {
            TenantContext.clear();
        }
    }

    private void toDlq(Map<Object, Object> f, String error) {
        try {
            Map<String, Object> m = new LinkedHashMap<>(toStringKeyMap(f));
            m.put("error", error.length() > 500 ? error.substring(0, 500) : error);
            redis.opsForStream().add(
                    org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                            .ofObject(m).withStreamKey(DLQ));
        } catch (Exception ex) {
            log.warn("dlq write failed: {}", ex.toString());
        }
    }

    /** stream 消息字段 Map<Object,Object> → 字符串键 Map（模板/路由取变量用）。 */
    private static Map<String, Object> toStringKeyMap(Map<Object, Object> f) {
        Map<String, Object> out = new java.util.HashMap<>();
        f.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private void ack(String recordId) {
        try {
            redis.opsForStream().acknowledge(STREAM, GROUP, recordId);
        } catch (Exception e) {
            log.warn("xack failed: {}", e.toString());
        }
    }
}