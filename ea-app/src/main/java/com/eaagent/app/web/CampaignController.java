package com.eaagent.app.web;

import com.eaagent.api.dto.CampaignWriteRequest;
import com.eaagent.app.service.CampaignDeliveryService;
import com.eaagent.common.PageResult;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.action.ActionContext;
import com.eaagent.ontology.action.ActionRegistry;
import com.eaagent.ontology.action.ActionRequest;
import com.eaagent.ontology.service.ObjectApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 活动（campaign）：查询走统一对象 API，投递日志经 CampaignDeliveryService；
 * 写操作经 Action 框架（createCampaign / updateCampaign / pauseCampaign / sendTouch，
 * 权限矩阵见详细设计 9.2）。Controller 不直调 mapper。
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final ObjectApiService objectApi;
    private final ActionRegistry actionRegistry;
    private final CampaignDeliveryService deliveryService;

    public CampaignController(ObjectApiService objectApi, ActionRegistry actionRegistry,
                              CampaignDeliveryService deliveryService) {
        this.objectApi = objectApi;
        this.actionRegistry = actionRegistry;
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) Integer limit) {
        com.eaagent.api.dto.ObjectQueryRequest req = new com.eaagent.api.dto.ObjectQueryRequest();
        req.setFilter(filter);
        req.setSort(sort == null ? "-created_at" : sort);
        req.setPageToken(pageToken);
        req.setLimit(limit);
        return Result.ok(objectApi.search("campaign", req));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CampaignWriteRequest req) {
        ActionContext ctx = actionCtx("campaign:" + java.util.UUID.randomUUID());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", req.getName());
        args.put("audience_id", req.getAudienceId());
        args.put("channel", req.getChannel());
        args.put("template_id", req.getTemplateId());
        args.put("schedule", req.getSchedule() == null ? null : req.getSchedule().toString());
        args.put("cron", req.getCron());
        args.put("template_routing", req.getTemplateRouting());
        args.put("trigger_rule", req.getTriggerRule());
        return Result.ok(actionRegistry.get("createCampaign").execute(ctx, ActionRequest.of(args)).data());
    }

    /** PUT /api/campaigns/{id}：编辑落库（updateCampaign，传入覆盖、缺失保留；DTO 字段全可空）。 */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody CampaignWriteRequest req) {
        ActionContext ctx = actionCtx("campaign:" + id + ":" + java.util.UUID.randomUUID());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("campaign_id", id);
        args.put("name", req.getName());
        args.put("audience_id", req.getAudienceId());
        args.put("channel", req.getChannel());
        args.put("template_id", req.getTemplateId());
        args.put("schedule", req.getSchedule() == null ? null : req.getSchedule().toString());
        args.put("cron", req.getCron());
        args.put("template_routing", req.getTemplateRouting());
        args.put("trigger_rule", req.getTriggerRule());
        return Result.ok(actionRegistry.get("updateCampaign").execute(ctx, ActionRequest.of(args)).data());
    }

    @PostMapping("/{id}/pause")
    public Result<Map<String, Object>> pause(@PathVariable Long id) {
        return Result.ok(actionRegistry.get("pauseCampaign")
                .execute(actionCtx("pause:" + id + ":" + java.util.UUID.randomUUID()), ActionRequest.of(Map.of("campaign_id", id))).data());
    }

    @PostMapping("/{id}/trigger")
    public Result<Map<String, Object>> trigger(@PathVariable Long id) {
        return Result.ok(actionRegistry.get("sendTouch")
                .execute(actionCtx("trigger:" + id + ":" + java.util.UUID.randomUUID()), ActionRequest.of(Map.of("campaign_id", id))).data());
    }

    /**
     * GET /api/campaigns/{id}/deliveries：活动投递日志（触发人员列表 + 投递明细）。游标分页
     * （PageToken，offset 语义同对象 API）+ 富化客户联系信息（phone/email 掩码、external_id、
     * attributes.name）；租户隔离，倒序稳定排序（created_at, id）。
     */
    @GetMapping("/{id}/deliveries")
    public Result<PageResult<Map<String, Object>>> deliveries(
            @PathVariable Long id,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) Integer limit) {
        return Result.ok(deliveryService.pageDeliveries(
                TenantContext.requiredTenantId(), id, pageToken, limit));
    }

    private ActionContext actionCtx(String requestId) {
        return ActionContext.of(TenantContext.requiredTenantId(),
                TenantContext.userId(), TenantContext.role(), requestId);
    }
}