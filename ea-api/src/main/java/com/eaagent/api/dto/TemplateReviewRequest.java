package com.eaagent.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 人工模板批注/终审；version 对应用户看到的模板原文，身份从登录上下文获取。 */
@Data
public class TemplateReviewRequest {
    @NotNull
    @Min(0)
    private Long version;
    @NotNull
    @Pattern(regexp = "COMMENT|APPROVE|REJECT")
    private String decision;
    @NotNull
    @Size(max = 2000)
    private String comment;
    @Positive
    private Long chatId;
}
