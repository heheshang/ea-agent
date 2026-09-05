package com.eaagent.channel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.CryptoService;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.ChannelConfigMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.model.ChannelConfigEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * SMS 通道真实适配器（6.4）：配置从 channel_config 按 (tenant_id, channel=sms) 加载并解密；
 * 发送 = HTTP POST {endpoint}/send（form：phone/content/idempotency_key/sign_name，
 * 头 X-Api-Key/X-Api-Secret），网关返回 message_id 即 SENT；回执查询 = POST {endpoint}/receipt。
 * 无凭据/未启用 → E-14001 CHANNEL_NOT_CONFIGURED；网关不可达 → E-14002；网关拒绝/无 message_id → E-14003。
 */
@Component
public class SmsChannelAdapter implements ChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(SmsChannelAdapter.class);

    private final ChannelConfigMapper channelConfigMapper;
    private final DeliveryMapper deliveryMapper;
    private final CryptoService cryptoService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public SmsChannelAdapter(ChannelConfigMapper channelConfigMapper, DeliveryMapper deliveryMapper,
                             CryptoService cryptoService) {
        this.channelConfigMapper = channelConfigMapper;
        this.deliveryMapper = deliveryMapper;
        this.cryptoService = cryptoService;
    }

    @Override
    public String channelType() {
        return "sms";
    }

    /** 配置字段：endpoint（必填，形如 http://gw:8090/sms）、apiKey、apiSecret、signName。 */
    private Map<String, Object> loadConfig(Long tenantId) {
        ChannelConfigEntity cfg = channelConfigMapper.selectOne(new QueryWrapper<ChannelConfigEntity>()
                .eq(ChannelConfigEntity.COL_TENANT_ID, tenantId)
                .eq(ChannelConfigEntity.COL_CHANNEL, "sms")
                .last("LIMIT 1"));
        if (cfg == null || Boolean.FALSE.equals(cfg.getEnabled())) {
            throw new BizException(ErrorCode.CHANNEL_NOT_CONFIGURED, "短信通道未配置");
        }
        String json = cryptoService.decrypt(tenantId, cfg.getConfigEncrypted());
        Map<String, Object> config = com.eaagent.common.JsonUtils.readMap(json);
        if (!StringUtils.hasText((String) config.get("endpoint"))) {
            throw new BizException(ErrorCode.CHANNEL_NOT_CONFIGURED, "短信通道缺少 endpoint");
        }
        return config;
    }

    @Override
    public void validate(Long tenantId, Map<String, Object> config) {
        // 调用方不传凭据：以 channel_config 落库配置为准；租户由调用方显式传入（异步线程无 TenantContext）
        loadConfig(tenantId);
    }

    @Override
    public String send(DeliveryMessage message) {
        Map<String, Object> config = loadConfig(message.tenantId());
        if (!StringUtils.hasText(message.to())) {
            throw new BizException(ErrorCode.SEND_FAILED, "联系人缺少手机号(sms)");
        }
        String endpoint = String.valueOf(config.get("endpoint")).replaceAll("/+$", "");
        String body = form(
                "phone", message.to(),
                "content", message.templateContent(),
                "idempotency_key", String.valueOf(message.deliveryId()),
                "sign_name", nvl(config.get("signName")));
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint + "/send"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (config.get("apiKey") != null) {
            rb.header("X-Api-Key", String.valueOf(config.get("apiKey")));
        }
        if (config.get("apiSecret") != null) {
            rb.header("X-Api-Secret", String.valueOf(config.get("apiSecret")));
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[SMS_CHANNEL] gateway unreachable tenant={} delivery={} err={}", message.tenantId(),
                    message.deliveryId(), e.getMessage());
            throw new BizException(ErrorCode.CHANNEL_UNAVAILABLE, "短信网关不可达: " + e.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("[SMS_CHANNEL] gateway reject tenant={} delivery={} http={} body={}", message.tenantId(),
                    message.deliveryId(), resp.statusCode(), resp.body());
            throw new BizException(ErrorCode.SEND_FAILED, "短信网关拒绝 http=" + resp.statusCode());
        }
        String msgId;
        try {
            Map<String, Object> parsed = com.eaagent.common.JsonUtils.readMap(resp.body());
            msgId = String.valueOf(parsed.get("message_id"));
        } catch (Exception e) {
            msgId = null;
        }
        if (!StringUtils.hasText(msgId) || "null".equals(msgId)) {
            throw new BizException(ErrorCode.SEND_FAILED, "短信网关响应缺少 message_id");
        }
        // 回写 delivery：SENT + 通道侧 message_id
        DeliveryEntity d = deliveryMapper.selectOne(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_ID, message.deliveryId())
                .eq(DeliveryEntity.COL_TENANT_ID, message.tenantId()));
        if (d == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        d.setStatus("SENT");
        d.setChannelMsgId(msgId);
        d.setError(null);
        d.setAttempt((d.getAttempt() == null ? 0 : d.getAttempt()) + 1);
        d.setUpdatedAt(Instant.now());
        deliveryMapper.updateById(d);
        log.info("[SMS_CHANNEL] sent tenant={} delivery={} to={} msgId={}", message.tenantId(),
                message.deliveryId(), message.to(), msgId);
        return msgId;
    }

    @Override
    public String queryReceipt(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return "UNKNOWN";
        }
        // 回执查询：POST {endpoint}/receipt；网关不可达返回 UNKNOWN（不抛错，查询非关键路径）
        try {
            DeliveryEntity d = deliveryMapper.selectOne(new QueryWrapper<DeliveryEntity>()
                    .eq(DeliveryEntity.COL_CHANNEL_MSG_ID, messageId).last("LIMIT 1"));
            if (d == null) {
                return "UNKNOWN";
            }
            Map<String, Object> config = loadConfig(d.getTenantId());
            String endpoint = String.valueOf(config.get("endpoint")).replaceAll("/+$", "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/receipt"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form("message_id", messageId), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return "UNKNOWN";
            }
            Map<String, Object> parsed = com.eaagent.common.JsonUtils.readMap(resp.body());
            return String.valueOf(parsed.get("status"));
        } catch (Exception e) {
            log.debug("[SMS_CHANNEL] receipt query failed msgId={} err={}", messageId, e.getMessage());
            return "UNKNOWN";
        }
    }

    private static String nvl(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}