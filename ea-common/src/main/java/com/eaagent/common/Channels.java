package com.eaagent.common;

import java.util.List;
import java.util.Set;

/**
 * 通道类型常量（渠道注册/校验/枚举共用）。值集合与 {@code channel} 列、ChannelAdapterRegistry 注册类型对齐。
 */
public final class Channels {
    /** 平台支持的通道类型（顺序即前端下拉/枚举展示顺序）。 */
    public static final List<String> ALL = List.of("sms", "email", "wechat", "push", "console");
    /** 同上，Set 形态（成员校验用）。 */
    public static final Set<String> ALL_SET = Set.copyOf(ALL);

    private Channels() {
    }
}
