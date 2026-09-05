package com.eaagent.channel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.CryptoService;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.mapper.ChannelConfigMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.model.ChannelConfigEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Email 通道适配器（6.4）：配置从 channel_config 按 (tenant_id, channel=email) 加载并解密；
 * 已配置 = HTTP POST {endpoint}/send（form：to/subject/content/idempotency_key，
 * 头 X-Api-Key/X-Api-Secret），网关返回 message_id 即 SENT；回执查询 POST {endpoint}/receipt。
 * 未配置/未启用 → console 降级（结构化日志 + delivery 回写 SENT，channel_msg_id=deliveryId），
 * 与历史行为一致，保证无配置时全链路仍可演示。
 */
@Component
public class EmailChannelAdapter implements ChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);

    @Value("${ea.channels.console-enabled:true}")
    private boolean consoleEnabled;

    private final ChannelConfigMapper channelConfigMapper;
    private final DeliveryMapper deliveryMapper;
    private final CryptoService cryptoService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public EmailChannelAdapter(ChannelConfigMapper channelConfigMapper, DeliveryMapper deliveryMapper,
                               CryptoService cryptoService) {
        this.channelConfigMapper = channelConfigMapper;
        this.deliveryMapper = deliveryMapper;
        this.cryptoService = cryptoService;
    }

    @Override
    public String channelType() {
        return "email";
    }

    /** 配置字段：endpoint（必填，形如 http://gw:8090/email）、apiKey、apiSecret；返回 null = 未配置（console 降级）。 */
    private Map<String, Object> loadConfig(Long tenantId) {
        ChannelConfigEntity cfg = channelConfigMapper.selectOne(new QueryWrapper<ChannelConfigEntity>()
                .eq(ChannelConfigEntity.COL_TENANT_ID, tenantId)
                .eq(ChannelConfigEntity.COL_CHANNEL, "email")
                .last("LIMIT 1"));
        if (cfg == null || Boolean.FALSE.equals(cfg.getEnabled())) {
            return null;
        }
        String json = cryptoService.decrypt(tenantId, cfg.getConfigEncrypted());
        Map<String, Object> config = JsonUtils.readMap(json);
        if (!StringUtils.hasText((String) config.get("endpoint"))) {
            return null;
        }
        return config;
    }

    @Override
    public void validate(Long tenantId, Map<String, Object> config) {
        // email 允许未配置：走 console 降级兜底（不抛 E-14001）
    }

    @Override
    public String send(DeliveryMessage message) {
        Map<String, Object> config = loadConfig(message.tenantId());
        if (config == null) {
            return consoleSend(message);
        }
        if (!StringUtils.hasText(message.to())) {
            throw new BizException(ErrorCode.SEND_FAILED, "联系人缺少邮箱(email)");
        }
        String endpoint = String.valueOf(config.get("endpoint")).replaceAll("/+$", "");
        String body = form(
                "to", message.to(),
                "subject", "EA 触达通知",
                "content", message.templateContent(),
                "idempotency_key", String.valueOf(message.deliveryId()));
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
            log.warn("[EMAIL_CHANNEL] gateway unreachable tenant={} delivery={} err={}", message.tenantId(),
                    message.deliveryId(), e.getMessage());
            throw new BizException(ErrorCode.CHANNEL_UNAVAILABLE, "邮件网关不可达: " + e.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("[EMAIL_CHANNEL] gateway reject tenant={} delivery={} http={} body={}", message.tenantId(),
                    message.deliveryId(), resp.statusCode(), resp.body());
            throw new BizException(ErrorCode.SEND_FAILED, "邮件网关拒绝 http=" + resp.statusCode());
        }
        String msgId;
        try {
            Map<String, Object> parsed = JsonUtils.readMap(resp.body());
            msgId = String.valueOf(parsed.get("message_id"));
        } catch (Exception e) {
            msgId = null;
        }
        if (!StringUtils.hasText(msgId) || "null".equals(msgId)) {
            throw new BizException(ErrorCode.SEND_FAILED, "邮件网关响应缺少 message_id");
        }
        markSent(message, msgId);
        log.info("[EMAIL_CHANNEL] sent tenant={} delivery={} to={} msgId={}", message.tenantId(),
                message.deliveryId(), message.to(), msgId);
        return msgId;
    }

    @Override
    public String queryReceipt(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return "UNKNOWN";
        }
        try {
            DeliveryEntity d = deliveryMapper.selectOne(new QueryWrapper<DeliveryEntity>()
                    .eq(DeliveryEntity.COL_CHANNEL_MSG_ID, messageId).last("LIMIT 1"));
            if (d == null) {
                return "UNKNOWN";
            }
            Map<String, Object> config = loadConfig(d.getTenantId());
            if (config == null) {
                return d.getStatus(); // console 降级：状态即 delivery 状态
            }
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
            Map<String, Object> parsed = JsonUtils.readMap(resp.body());
            return String.valueOf(parsed.get("status"));
        } catch (Exception e) {
            log.debug("[EMAIL_CHANNEL] receipt query failed msgId={} err={}", messageId, e.getMessage());
            return "UNKNOWN";
        }
    }

    /** console 降级发送（历史行为）：结构化日志 + delivery 回写 SENT（channel_msg_id=deliveryId）。 */
    private String consoleSend(DeliveryMessage message) {
        if (!consoleEnabled) {
            throw new BizException(ErrorCode.CHANNEL_NOT_CONFIGURED);
        }
        log.info("[CONSOLE_CHANNEL] deliveryId={} tenantId={} customerId={} channel={} to={} abGroup={} content={}",
                message.deliveryId(), message.tenantId(), message.customerId(), message.channel(),
                message.to(), message.abGroup(), message.templateContent());
        markSent(message, String.valueOf(message.deliveryId()));
        return String.valueOf(message.deliveryId());
    }

    private void markSent(DeliveryMessage message, String msgId) {
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
    }

    private static String nvl(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(kv[i]).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}