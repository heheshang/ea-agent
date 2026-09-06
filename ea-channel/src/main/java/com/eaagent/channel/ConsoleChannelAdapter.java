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
 * Console 降级通道（6.3 / 闭环）：发送 = 结构化日志 + delivery 状态回写 SENT（channel_msg_id=deliveryId），
 * 回执回调即转 DELIVERED 可由 controller 直接落库。无外部依赖，全链路可演示。
 */
@Component
public class ConsoleChannelAdapter implements ChannelAdapter {
    private static final Logger log = LoggerFactory.getLogger(ConsoleChannelAdapter.class);

    @Value("${ea.channels.console-enabled:true}")
    private boolean consoleEnabled;

    private final DeliveryMapper deliveryMapper;

    public ConsoleChannelAdapter(DeliveryMapper deliveryMapper) {
        this.deliveryMapper = deliveryMapper;
    }

    @Override
    public String channelType() {
        return "console";
    }

    @Override
    public void validate(Long tenantId, Map<String, Object> config) {
        if (!consoleEnabled) {
            throw new BizException(ErrorCode.CHANNEL_NOT_CONFIGURED);
        }
    }

    @Override
    public String send(DeliveryMessage message) {
        validate(message.tenantId(), Map.of());
        log.info("[CONSOLE_CHANNEL] deliveryId={} tenantId={} customerId={} channel={} to={} content={}",
                message.deliveryId(), message.tenantId(), message.customerId(), message.channel(),
                message.to(), message.templateContent());
        DeliveryEntity d = deliveryMapper.selectOne(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_ID, message.deliveryId()).eq(DeliveryEntity.COL_TENANT_ID, message.tenantId()));
        if (d == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        d.setStatus(DeliveryEntity.STATUS_SENT);
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