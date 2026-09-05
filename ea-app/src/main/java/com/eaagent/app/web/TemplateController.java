package com.eaagent.app.web;

import com.eaagent.api.dto.TemplateWriteRequest;
import com.eaagent.app.service.TemplateService;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.model.TemplateEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模板管理（/api/templates）：租户维度 CRUD + 审核流
 * （submit 提交 PENDING → Review REVIEWER 审批）。写操作 OPERATOR、审批 REVIEWER。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public Result<List<TemplateEntity>> list(@RequestParam(required = false) String channel) {
        return Result.ok(templateService.list(TenantContext.requiredTenantId(), channel));
    }

    @GetMapping("/{id}")
    public Result<TemplateEntity> get(@PathVariable Long id) {
        return Result.ok(templateService.get(TenantContext.requiredTenantId(), id));
    }

    @PostMapping
    public Result<TemplateEntity> create(@Valid @RequestBody TemplateWriteRequest req) {
        return Result.ok(templateService.create(TenantContext.requiredTenantId(),
                TenantContext.userId(), req));
    }

    @PutMapping("/{id}")
    public Result<TemplateEntity> update(@PathVariable Long id, @Valid @RequestBody TemplateWriteRequest req) {
        return Result.ok(templateService.update(TenantContext.requiredTenantId(), id, req));
    }

    /** 提交审核：DRAFT|REJECTED → PENDING。 */
    @PostMapping("/{id}/submit")
    public Result<TemplateEntity> submit(@PathVariable Long id) {
        return Result.ok(templateService.submit(TenantContext.requiredTenantId(), id));
    }

    /** 通过：REVIEWER；PENDING → APPROVED。 */
    @PostMapping("/{id}/approve")
    public Result<TemplateEntity> approve(@PathVariable Long id) {
        return Result.ok(templateService.review(TenantContext.requiredTenantId(), id,
                TenantContext.role(), true));
    }

    /** 驳回：REVIEWER；PENDING → REJECTED。 */
    @PostMapping("/{id}/reject")
    public Result<TemplateEntity> reject(@PathVariable Long id) {
        return Result.ok(templateService.review(TenantContext.requiredTenantId(), id,
                TenantContext.role(), false));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.delete(TenantContext.requiredTenantId(), id);
        return Result.ok(null);
    }
}