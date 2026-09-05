package com.eaagent.channel;

import java.util.Map;

/**
 * 通道适配器（6.1）：发送与回执查询两件事；配置校验在 adjust/config 变更时执行。
 * registry 通过 Spring 构造收集；未启用/未配置的适配器由自身在 send 前抛错。
 */
public interface ChannelAdapter {

    /** 通道类型标识（与 campaign.channel / channel_config.channel 一致）。 */
    String channelType();

    /** 校验通道配置（改配置/测试发送时调用）；无效抛 BizException E-14002。租户显式传入（异步线程无 TenantContext）。 */
    void validate(Long tenantId, Map<String, Object> config);

    /** 发送：返回通道侧消息 ID（console 降级 = deliveryId）；失败抛 E-1400x。 */
    String send(DeliveryMessage message);

    /** 回执查询：返回最新状态（SENT/DELIVERED/BOUNCED/FAILED…）。 */
    String queryReceipt(String messageId);
}