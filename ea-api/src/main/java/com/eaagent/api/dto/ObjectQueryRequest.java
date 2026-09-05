package com.eaagent.api.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 统一对象查询请求（详细设计 3.2）。 */
@Data
@NoArgsConstructor
public class ObjectQueryRequest {
    private String filter;      // DSL：status == 'active' AND tag CONTAINS ['high_value']
    private String sort;        // -created_at
    private String pageToken;
    private Integer limit;
    private String keyword;     // 模糊查询（客户：姓名/手机/邮箱/外部 ID）
}