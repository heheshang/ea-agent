package com.eaagent.app.web;

import com.eaagent.app.service.RetentionService;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存留看板（/api/retention）：触达客户回访留存——首次成功触达后 D1/D3/D7/D30 累计回访率。
 * 可选过滤：campaign_id（活动维）、event_type（目标回访事件，空=全部事件）。
 */
@RestController
@RequestMapping("/api/retention")
public class RetentionController {

    private final RetentionService retentionService;

    public RetentionController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @GetMapping
    public Result<Map<String, Object>> retention(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long campaign_id,
            @RequestParam(required = false) String event_type) {
        return Result.ok(retentionService.retention(TenantContext.requiredTenantId(),
                Math.max(1, Math.min(days, 90)), campaign_id,
                event_type == null || event_type.isBlank() ? null : event_type));
    }
}