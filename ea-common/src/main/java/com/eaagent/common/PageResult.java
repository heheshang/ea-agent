package com.eaagent.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cursor 分页结果（详细设计 3.2：items + next_page_token + total；不做 OFFSET 大翻页）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private List<T> items;
    private String nextPageToken;
    private long total;
}