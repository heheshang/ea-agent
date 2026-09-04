package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/** 通道配置写入请求（6.2：config 明文仅在服务端信封加密后落库）。 */
@Data
public class ChannelWriteRequest {
    @NotBlank
    private String channel;              // sms|email|wechat|push|console
    private Map<String, Object> config;  // 通道连接参数（写入即加密）
    @NotBlank
    private Boolean enabled;
    private Map<String, Object> frequencyLimit; // {max_per_day, quiet_hours}
}