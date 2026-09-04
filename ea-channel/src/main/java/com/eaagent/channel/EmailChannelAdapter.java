package com.eaagent.channel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.model.DeliveryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;

/**
 * Email 通道（6.3 降级契约）：未配置 SMTP 凭据 → 降级 console（结构化日志 + delivery 回写 SENT，
 * channel_msg_id=deliveryId），写法与 ConsoleChannelAdapter 同范式；接入真实 SMTP 时在此替换发送逻辑。
 */
@Component
public class EmailChannelAdapter implements ChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);

    @Value("${ea.channels.console-enabled:true}")
    private boolean consoleEnabled;

    private final DeliveryMapper deliveryMapper;

    public EmailChannelAdapter(DeliveryMapper deliveryMapper) {
        this.deliveryMapper = deliveryMapper;
    }

    @Override
    public String channelType() {
        return "email";
    }

    @Override
    public void validate(Map<String, Object> config) {
        if (!consoleEnabled) {
            throw new BizException(ErrorCode.CHANNEL_NOT_CONFIGURED);
        }
    }

    @Override
    public String send(DeliveryMessage message) {
        validate(Map.of());
        log.info("[CONSOLE_CHANNEL] deliveryId={} tenantId={} customerId={} channel={} to={} abGroup={} content={}",
                message.deliveryId(), message.tenantId(), message.customerId(), message.channel(),
                message.to(), message.abGroup(), message.templateContent());
        DeliveryEntity d = deliveryMapper.selectOne(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_ID, message.deliveryId()).eq(DeliveryEntity.COL_TENANT_ID, message.tenantId()));
        if (d == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        d.setStatus("SENT");
        d.setChannelMsgId(String.valueOf(message.deliveryId()));
        d.setError(null);
        d.setAttempt((d.getAttempt() == null ? 0 : d.getAttempt()) + 1);
        d.setUpdatedAt(Instant.now());
        deliveryMapper.updateById(d);
        return String.valueOf(message.deliveryId());
    }

    @Override
    public String queryReceipt(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return "UNKNOWN";
        }
        DeliveryEntity d = deliveryMapper.selectById(Long.valueOf(messageId));
        return d == null ? "UNKNOWN" : d.getStatus();
    }
}