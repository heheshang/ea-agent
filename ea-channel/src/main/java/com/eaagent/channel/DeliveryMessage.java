package com.eaagent.channel;

/**
 * 通道发送消息（6.1）：通道只认这条契约；模板渲染由上层完成。
 */
public record DeliveryMessage(
        Long deliveryId,
        Long tenantId,
        Long customerId,
        String channel,
        String to,             // phone / email / openid
        String templateContent,
        String abGroup) {
}