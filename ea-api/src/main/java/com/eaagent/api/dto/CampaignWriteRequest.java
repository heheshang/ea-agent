package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 任务写入请求（创建/更新 campaign；AB 配置变更走既有审批门控）。 */
@Data
public class CampaignWriteRequest {
    @NotBlank
    private String name;
    @NotNull
    private Long audienceId;
    @NotBlank
    private String channel;              // sms|email|wechat|push|console
    @NotNull
    private Long templateId;
    private Instant schedule;            // 一次性时间
    private String cron;                 // 周期任务
    private Integer grayRatio;           // 默认 100
    private String abMode;               // NONE|AB
    private Integer abSplit;
    private List<Map<String, Object>> abVariants;
    private List<Map<String, Object>> templateRouting;   // 规则→模板 jsonb；null=保留/不配
    private Map<String, Object> triggerRule;   // {event_type, window, cooldown}；cooldown/window 支持 1h/30m/2d/90s/ISO-8601，保存时归一 ISO
}