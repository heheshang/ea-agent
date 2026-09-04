package com.eaagent.ontology.function;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.model.DeliveryEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * frequencyCheck：频控 / 冷却检查（4.2 Call Function，决策咨询）。
 * 自原有同名工具收编：只读返回发送总量与冷却键；真正拦截在 SendTouchAction 管线（H）。
 */
@Component
public class FrequencyCheckFunction implements Function {

    private final DeliveryMapper deliveryMapper;

    public FrequencyCheckFunction(DeliveryMapper deliveryMapper) {
        this.deliveryMapper = deliveryMapper;
    }

    @Override
    public String name() {
        return "frequencyCheck";
    }

    @Override
    public String description() {
        return "频控检查：返回客户在指定通道的历史发送总量与冷却状态键（决策咨询，拦截在 Action）";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of(
                "customer_id", Map.of("type", "integer", "description", "客户 ID"),
                "channel", Map.of("type", "string", "description", "通道类型（console/email/wechat 等）"));
    }

    @Override
    public Map<String, Object> execute(long tenantId, Map<String, Object> args) {
        long cid = FunctionArgs.requireLong(args, "customer_id");
        String channel = FunctionArgs.requireString(args, "channel");
        Long sentTotal = deliveryMapper.selectCount(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_TENANT_ID, tenantId)
                .eq(DeliveryEntity.COL_CUSTOMER_ID, cid)
                .eq(DeliveryEntity.COL_CHANNEL, channel));
        return Map.of("customer_id", cid, "channel", channel,
                "sent_total", sentTotal == null ? 0L : sentTotal,
                "cooling_key", "ea:cd:" + tenantId + ":" + cid);
    }
}