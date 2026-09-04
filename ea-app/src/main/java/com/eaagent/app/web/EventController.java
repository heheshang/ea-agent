package com.eaagent.app.web;

import com.eaagent.api.dto.EventImportRequest;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.model.EventEntity;
import com.eaagent.ontology.service.EventService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 事件导入（7.1 POST /api/events = importEvents）：幂等键 (tenant_id, X-Request-Id)，
 * 落库后发布 EA-Bus（触发管线见详细设计 10.3）。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public Result<Map<String, Object>> importEvents(@RequestBody EventImportRequest req,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long tenantId = TenantContext.requiredTenantId();
        String dedupKey = requestId != null && !requestId.isBlank()
                ? "req:" + requestId
                : eventDedup(req);
        EventEntity event = eventService.ingest(tenantId, req.getCustomerId(), req.getCustomerExternalId(),
                req.getEventType(), req.getPayload(), dedupKey);
        return Result.ok(Map.of(
                "event_id", event.getId(),
                "created", event.getCreatedAt().toString(),
                "dedup_key", event.getDedupKey()));
    }

    /** 无 request_id 时的内容幂等键：客户 + 事件类型 + payload 摘要。 */
    private String eventDedup(EventImportRequest req) {
        String key = req.getCustomerId() + "|" + req.getEventType() + "|"
                + (req.getPayload() == null ? "" : req.getPayload().toString());
        return "sha:" + Integer.toHexString(key.hashCode());
    }
}