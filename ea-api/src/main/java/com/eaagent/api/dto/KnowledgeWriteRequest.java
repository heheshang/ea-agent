package com.eaagent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 知识库写入请求（创建/更新共用）。
 * 更新为「传入覆盖、缺失保留」：null 字段跳过，仅 title/content 传值且 blank 时报 E-10001。
 */
@Data
public class KnowledgeWriteRequest {
    private String title;      // 非空（创建必填；更新时如有传值须非 blank）
    private String content;    // 非空
    private List<String> tags; // 可空，默认空数组
    private Boolean enabled;   // 可空，创建默认 true
}