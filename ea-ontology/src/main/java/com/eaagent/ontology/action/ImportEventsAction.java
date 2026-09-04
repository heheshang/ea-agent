package com.eaagent.ontology.action;

import com.eaagent.common.IdempotencyService;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.model.EventEntity;
import com.eaagent.ontology.service.EventService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * importEvents（3.4 / 10.3）：事件导入即 EA-Bus 入站。幂等由 EventService 承载
 * （(tenant_id, dedup_key) 唯一）；重复 dedup 返回首次记录（不重复发送）——系统/Agent 双入口共用。
 */
@Component
public class ImportEventsAction extends AbstractAction {

    private final EventService eventService;

    public ImportEventsAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                              StringRedisTemplate redis, EventService eventService) {
        super(actionLogMapper, idempotencyService, redis);
        this.eventService = eventService;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("importEvents")
                .description("导入业务事件（触发流水线入口）")
                .requiredArgs(List.of("event_type", "dedup_key"))
                .permissions(List.of())
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        EventEntity event = eventService.ingest(
                ctx.tenantId(),
                req.getLong("customer_id"),
                req.getString("customer_external_id"),
                req.getString("event_type"),
                req.getMap("payload"),
                req.getString("dedup_key"));
        Map<String, Object> out = new HashMap<>();
        out.put("event_id", event.getId());
        out.put("created", event.getCreatedAt());
        return out;
    }
}