package com.eaagent.ontology.action;

import com.eaagent.common.Actors;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.common.JsonUtils;
import com.eaagent.common.Roles;
import com.eaagent.common.Texts;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.model.ActionLogEntity;
import com.eaagent.ontology.service.JsonMasker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Action 模板管线（3.4 / 10.1）：
 * 鉴权 → 租户 → 幂等（SETNX + 首次结果缓存，重放返回首次结果）→ 参数/业务校验（E-13002）
 * → 权限（E-10003）→ doExecute → 审计 action_log（幂等窗口 TTL 内缓存结果供回放）。
 * 子类仅实现 doExecute 与 meta。
 */
@RequiredArgsConstructor
public abstract class AbstractAction implements Action {
    private static final Logger log = LoggerFactory.getLogger(AbstractAction.class);
    private static final Duration IDEM_TTL = Duration.ofHours(1);

    protected final ActionLogMapper actionLogMapper;
    protected final IdempotencyService idempotencyService;
    protected final StringRedisTemplate redis;

    /** 审计 actor 类型：USER | AGENT | SYSTEM（默认 USER）。 */
    protected String actorType(ActionContext ctx) {
        return ctx.userId() == null ? Actors.SYSTEM : Actors.USER;
    }

    protected abstract Map<String, Object> doExecute(ActionContext ctx, ActionRequest req);

    @Override
    public ActionResult execute(ActionContext ctx, ActionRequest req) {
        String requestId = ctx.requestId();
        log.info("action dispatch name={} tenantId={} requestId={} args={}",
                meta().name(), ctx.tenantId(), requestId, truncateJson(req.args(), 200));
        // 1. 幂等抢占（含重放返回首次结果）
        if (requestId != null && !requestId.isBlank()) {
            if (!idempotencyService.tryAcquire(ctx.tenantId(), requestId, IDEM_TTL)) {
                String cached = redis.opsForValue().get(idempotencyService.key(ctx.tenantId(), requestId));
                if (cached != null) {
                    log.warn("action idempotent replay name={} tenantId={} requestId={}",
                            meta().name(), ctx.tenantId(), requestId);
                    return JsonUtils.read(cached, ActionResult.class);
                }
                log.warn("action idempotency conflict name={} tenantId={} requestId={} code=E-13003",
                        meta().name(), ctx.tenantId(), requestId);
                throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
        }
        try {
            // 2. 鉴权
            if (ctx.tenantId() == null) {
                throw new BizException(ErrorCode.TENANT_CONTEXT_MISSING);
            }
            if (ctx.userId() == null && !Actors.SYSTEM.equals(actorType(ctx)) && !Actors.AGENT.equals(actorType(ctx))) {
                throw new BizException(ErrorCode.UNAUTHENTICATED);
            }
            // 3. 参数校验
            for (String arg : meta().requiredArgs()) {
                if (!req.args().containsKey(arg) || req.get(arg) == null) {
                    throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED);
                }
            }
            // 4. 权限校验
            checkPermission(ctx);
            // 5. 业务执行
            Map<String, Object> data = doExecute(ctx, req);
            log.info("action done name={} tenantId={} requestId={} ok=true",
                    meta().name(), ctx.tenantId(), requestId);
            // 6. 审计（不阻断主流程）
            audit(ctx, req, data);
            ActionResult result = ActionResult.ok(requestId, meta().name(), data);
            if (requestId != null && !requestId.isBlank()) {
                redis.opsForValue().set(idempotencyService.key(ctx.tenantId(), requestId),
                        JsonUtils.write(result), IDEM_TTL);
            }
            return result;
        } catch (BizException e) {
            // 业务失败（权限拒绝/校验失败/doExecute 失败原因）：WARN 记录（原因截断）
            log.warn("action failed name={} tenantId={} requestId={} code={} error={}",
                    meta().name(), ctx.tenantId(), requestId, e.getErrorCode().getCode(),
                    truncate(String.valueOf(e.getMessage()), 200));
            // 失败不计幂等完成：释放抢占锁，允许重试（否则锁残留 TTL 期内重放读占位值报错）
            if (requestId != null && !requestId.isBlank()) {
                idempotencyService.release(ctx.tenantId(), requestId);
            }
            throw e;
        } catch (Exception e) {
            log.error("action {} failed", meta().name(), e);
            if (requestId != null && !requestId.isBlank()) {
                idempotencyService.release(ctx.tenantId(), requestId);
            }
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, String.valueOf(e.getMessage()));
        }
    }

    private void checkPermission(ActionContext ctx) {
        if (meta().permissions().isEmpty()) {
            return;
        }
        int roleLevel = Roles.ROLE_LEVEL.getOrDefault(ctx.role(), 0);
        for (String perm : meta().permissions()) {
            int need = Roles.ROLE_LEVEL.getOrDefault(mapPermission(perm), 4);
            if (roleLevel >= need) {
                return;
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN);
    }

    /** meta.permissions 声明项 → 需要的最低角色；依赖具体声明的角色名直接匹配，此项为扩展点。 */
    protected String mapPermission(String permission) {
        return permission;
    }

    private void audit(ActionContext ctx, ActionRequest req, Map<String, Object> data) {
        try {
            ActionLogEntity logEntity = new ActionLogEntity();
            logEntity.setRequestId(ctx.requestId());
            logEntity.setTenantId(ctx.tenantId());
            logEntity.setActorType(actorType(ctx));
            logEntity.setActorId(ctx.userId() == null ? "sys" : String.valueOf(ctx.userId()));
            logEntity.setAction(meta().name());
            logEntity.setArgs(JsonMasker.mask(req.args()));
            logEntity.setResult(data == null ? null : JsonMasker.mask(data));
            logEntity.setCreatedAt(java.time.Instant.now());
            actionLogMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("action audit failed: {}", e.getMessage());
        }
    }

    /** 日志参数序列化：Map → JSON，失败回退 toString；截断防敏感/膨胀。 */
    private static String truncateJson(Object o, int limit) {
        String s;
        try {
            s = JsonUtils.write(o);
        } catch (Exception e) {
            s = String.valueOf(o);
        }
        return truncate(s, limit);
    }

    /** 日志长文本截断（防敏感/膨胀）。 */
    private static String truncate(String s, int limit) {
        return Texts.truncate(s, limit);
    }
}