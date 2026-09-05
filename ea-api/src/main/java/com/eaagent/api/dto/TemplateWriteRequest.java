package com.eaagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模板写入请求：vars 由后端从 content 的 {{占位符}} 自动提取，不随前端提交。 */
@Data
public class TemplateWriteRequest {
    @NotBlank
    private String title;
    @NotBlank
    private String channel;              // sms|email|wechat|push|console
    @NotNull
    private String content;
}