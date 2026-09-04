package com.eaagent.channel;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通道注册表（6.2）：构造收集全部 ChannelAdapter Bean；
 * get(type) 未知通道 E-14001 CHANNEL_INVALID。
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> byType;

    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.byType = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(ChannelAdapter::channelType, Function.identity()));
    }

    public ChannelAdapter get(String type) {
        ChannelAdapter adapter = byType.get(type);
        if (adapter == null) {
            throw new BizException(ErrorCode.CHANNEL_NOT_CONFIGURED);
        }
        return adapter;
    }

    public List<String> types() {
        return List.copyOf(byType.keySet());
    }
}