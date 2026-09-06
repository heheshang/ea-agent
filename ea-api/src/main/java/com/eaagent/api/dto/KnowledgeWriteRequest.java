package com.eaagent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 知识库写入请求（创建/更新共用）。
 * 更新为「传入覆盖、缺失保留」：null 字段跳过，仅 title/content 传值且 blank 时报 E-10001。
 * V14 本体化字段：recordType/lifecycle/supersedesId 可空——null 时创建取默认值(rule/active)、更新时保留原值。
 */
@Data
public class KnowledgeWriteRequest {
    private String title;          // 非空（创建必填；更新时如有传值须非 blank）
    private String content;        // 非空
    private List<String> tags;     // 可空，默认空数组
    private Boolean enabled;       // 可空，创建默认 true
    private String recordType;     // 可空：decision/constraint/rule/lesson/rationale/fact/anti_pattern
    private String lifecycle;      // 可空：active/superseded/obsolete
    private Long supersedesId;     // 可空：被本条取代的旧条目 id（同租户；设置时目标自动置 superseded）
}