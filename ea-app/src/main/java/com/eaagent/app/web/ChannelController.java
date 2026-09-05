package com.eaagent.app.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.api.dto.CallbackRequest;
import com.eaagent.channel.ChannelAdapterRegistry;
import com.eaagent.common.Actors;
import com.eaagent.common.BizException;
import com.eaagent.common.Channels;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Result;
import com.eaagent.common.TenantContext;
import com.eaagent.ontology.mapper.ChannelConfigMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.model.ChannelConfigEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.common.CryptoService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通道回执（7.1 POST /api/channels/{type}/callback，白名单）：租户回调密钥 HMAC 验签
 * （正文 messageId|status|timestamp 规范化）+ 时间窗防重放 + ea:cb: 幂等去重 → 回写 delivery。
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    private static final long SIGN_WINDOW_MS = 300_000;

    /** 通道回执可回写的终端状态（DELIVERED/BOUNCED/FAILED/UNSUBSCRIBED）；PENDING/SENT 为内部生命周期态，禁止客户端直写。 */
    private static final Set<String> CALLBACK_STATUSES = Set.of(
            DeliveryEntity.STATUS_DELIVERED, DeliveryEntity.STATUS_BOUNCED,
            DeliveryEntity.STATUS_FAILED, DeliveryEntity.STATUS_UNSUBSCRIBED);

    private final DeliveryMapper deliveryMapper;
    private final ChannelConfigMapper channelConfigMapper;
    private final CryptoService cryptoService;
    private final StringRedisTemplate redis;
    private final ChannelAdapterRegistry channelRegistry;

    public ChannelController(DeliveryMapper deliveryMapper, ChannelConfigMapper channelConfigMapper,
                             CryptoService cryptoService, StringRedisTemplate redis,
                             ChannelAdapterRegistry channelRegistry) {
        this.deliveryMapper = deliveryMapper;
        this.channelConfigMapper = channelConfigMapper;
        this.cryptoService = cryptoService;
        this.redis = redis;
        this.channelRegistry = channelRegistry;
    }

    /** GET /api/channels：实际注册通道枚举（固定顺序过滤 registry；前端下拉数据源）。 */
    @GetMapping
    public Result<List<String>> listChannels() {
        List<String> registered = channelRegistry.types();
        return Result.ok(Channels.ALL.stream()
                .filter(registered::contains)
                .toList());
    }

    @PostMapping("/{type}/callback")
    public Result<Map<String, Object>> callback(@PathVariable String type,
                                                @RequestBody CallbackRequest req,
                                                @RequestHeader(value = "X-Signature", required = false) String signature) {
        if (signature == null || signature.isBlank() || req.getMessageId() == null) {
            throw new BizException(ErrorCode.CALLBACK_SIGNATURE_INVALID, "missing signature or messageId");
        }
        DeliveryEntity delivery = deliveryMapper.selectOne(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_CHANNEL_MSG_ID, req.getMessageId()).last("LIMIT 1"));
        if (delivery == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "delivery not found for messageId");
        }
        Long tenantId = delivery.getTenantId();
        ChannelConfigEntity cfg = channelConfigMapper.selectOne(new QueryWrapper<ChannelConfigEntity>()
                .eq(ChannelConfigEntity.COL_TENANT_ID, tenantId)
                .eq(ChannelConfigEntity.COL_CHANNEL, delivery.getChannel())
                .last("LIMIT 1"));
        if (cfg == null || cfg.getCallbackSecret() == null) {
            throw new BizException(ErrorCode.CHANNEL_NOT_CONFIGURED, "callback secret not configured");
        }
        String secret = cryptoService.decrypt(tenantId, cfg.getCallbackSecret());
        String canonical = req.getMessageId() + "|" + req.getStatus() + "|" + req.getTimestamp();
        String expected = hmacSha256Hex(secret, canonical);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ErrorCode.CALLBACK_SIGNATURE_INVALID, "hmac mismatch");
        }
        if (req.getTimestamp() == null
                || Math.abs(System.currentTimeMillis() - req.getTimestamp()) > SIGN_WINDOW_MS) {
            throw new BizException(ErrorCode.CALLBACK_SIGNATURE_INVALID, "replay window exceeded");
        }
        if (req.getStatus() == null || !CALLBACK_STATUSES.contains(req.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "callback status not allowed: " + req.getStatus());
        }

        Boolean first = redis.opsForValue().setIfAbsent("ea:cb:" + req.getMessageId(),
                req.getStatus(), Duration.ofMinutes(10));
        if (first == null || !first) {
            return Result.ok(Map.of("message_id", req.getMessageId(), "status", delivery.getStatus(), "dup", true));
        }
        try {
            TenantContext.setIdentity(tenantId, null, Actors.SYSTEM);
            delivery.setChannelMsgId(req.getMessageId());
            delivery.setStatus(req.getStatus());
            delivery.setError(req.getError() != null && req.getError().length() > 500
                    ? req.getError().substring(0, 500) : req.getError());
            delivery.setAttempt(delivery.getAttempt() == null ? 1 : delivery.getAttempt() + 1);
            delivery.setUpdatedAt(Instant.now());
            deliveryMapper.updateById(delivery);
        } finally {
            TenantContext.clear();
        }
        return Result.ok(Map.of("message_id", req.getMessageId(), "status", delivery.getStatus(),
                "delivery_id", delivery.getId(), "dup", false));
    }

    private String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException(ErrorCode.CALLBACK_SIGNATURE_INVALID, "hmac computation failed");
        }
    }
}