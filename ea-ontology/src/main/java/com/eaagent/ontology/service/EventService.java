package com.eaagent.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.bus.StreamPublisher;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.EventMapper;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.EventEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 事件服务（5.3 / 10.3 步骤 1-2）：幂等入库 → 总线发布。
 * 幂等：(tenant_id, dedup_key) 唯一约束 + 先查后插；重复 dedup 返回首次记录（不重复发送）。
 * 客户解析：customer_id 优先；external_id 兜底；均缺失 → 自动创建客户（闭环可演示）。
 */
@Service
@RequiredArgsConstructor
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventMapper eventMapper;
    private final CustomerMapper customerMapper;
    private final StreamPublisher publisher;

    /** 幂等入库并发布总线；返回事件（含是否首次 created 标记）。 */
    public EventEntity ingest(Long tenantId, Long customerId, String customerExternalId,
                              String eventType, Map<String, Object> payload, String dedupKey) {
        long cid = resolveCustomer(tenantId, customerId, customerExternalId);
        // 幂等检查（唯一约束兜底；先查避免 DuplicateKey 噪音）
        EventEntity existing = eventMapper.selectOne(new QueryWrapper<EventEntity>()
                .eq(EventEntity.COL_TENANT_ID, tenantId).eq(EventEntity.COL_DEDUP_KEY, dedupKey).last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        EventEntity event = new EventEntity();
        event.setTenantId(tenantId);
        event.setCustomerId(cid);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setDedupKey(dedupKey);
        event.setCreatedAt(Instant.now());
        try {
            eventMapper.insert(event);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发双写：唯一约束兜底，返回已存在
            return eventMapper.selectOne(new QueryWrapper<EventEntity>()
                    .eq(EventEntity.COL_TENANT_ID, tenantId).eq(EventEntity.COL_DEDUP_KEY, dedupKey).last("LIMIT 1"));
        }
        try {
            publisher.publish(event);
        } catch (Exception e) {
            log.warn("event {} persisted but EA-Bus publish failed: {}", event.getId(), e.getMessage());
        }
        return event;
    }

    private long resolveCustomer(Long tenantId, Long customerId, String customerExternalId) {
        if (customerId != null) {
            CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                    .eq(CustomerEntity.COL_TENANT_ID, tenantId).eq(CustomerEntity.COL_ID, customerId).last("LIMIT 1"));
            if (c == null) {
                throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
            }
            return customerId;
        }
        if (customerExternalId != null && !customerExternalId.isBlank()) {
            CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                    .eq(CustomerEntity.COL_TENANT_ID, tenantId).eq(CustomerEntity.COL_EXTERNAL_ID, customerExternalId).last("LIMIT 1"));
            if (c != null) {
                return c.getId();
            }
            // 自动注册新客户（闭环演示：未知 externalId 事件自动建档）
            CustomerEntity created = new CustomerEntity();
            created.setTenantId(tenantId);
            created.setExternalId(customerExternalId);
            created.setStatus(CustomerEntity.STATUS_ACTIVE);
            created.setCreatedAt(Instant.now());
            created.setUpdatedAt(Instant.now());
            customerMapper.insert(created);
            return created.getId();
        }
        throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED);
    }
}