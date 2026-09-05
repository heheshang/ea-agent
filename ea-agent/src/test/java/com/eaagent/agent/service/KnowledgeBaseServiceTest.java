package com.eaagent.agent.service;

import com.eaagent.ontology.mapper.KnowledgeMapper;
import com.eaagent.ontology.model.KnowledgeEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseService 检索打分单测（内存打分，确定性可测）：
 * 覆盖切词、标题/标签/内容权重排序、enabled 过滤、阈值与 topK 截断、空/空白查询。
 */
class KnowledgeBaseServiceTest {

    private static KnowledgeEntity entry(String title, String content, List<String> tags, boolean enabled) {
        KnowledgeEntity e = new KnowledgeEntity();
        e.setTenantId(1L);
        e.setTitle(title);
        e.setContent(content);
        e.setTags(tags);
        e.setEnabled(enabled);
        e.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return e;
    }

    /** 模拟 selectList：给定全量候选，仅返回启用项（等价于 SQL eq(enabled,true)）。 */
    private static KnowledgeBaseService svc(List<KnowledgeEntity> candidates, int topK) {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.selectList(any())).thenAnswer(inv -> candidates.stream()
                .filter(KnowledgeEntity::getEnabled)
                .toList());
        return new KnowledgeBaseService(mapper, topK);
    }

    @Test
    void tokenizeCjkBigrams() {
        List<String> terms = KnowledgeBaseService.tokenize("触达退订规范");
        // 5 个字符 → 4 个二元组：触达/达退/退订/订规/规范
        assertEquals(List.of("触达", "达退", "退订", "订规", "规范"), terms);
        // 标点切分 + 单字去噪
        assertEquals(List.of("退订", "规范"), KnowledgeBaseService.tokenize("退订，规范！"));
        assertEquals(List.of(), KnowledgeBaseService.tokenize("一"));
        // latin 段整体小写保留
        assertEquals(List.of("ab", "退订"), KnowledgeBaseService.tokenize("AB 退订"));
    }

    @Test
    void titleHitRanksAboveTagHit() {
        KnowledgeEntity titleHit = entry("退订规范", "无关内容", List.of(), true);
        KnowledgeEntity tagHit = entry("无关标题", "无关内容", List.of("退订"), true);
        // query 命中标题条（3 个 bigram × 标题 +3 = 9）应排在仅标签命中（+2）之前
        List<KnowledgeBaseService.KnowledgeHit> hits =
                svc(List.of(tagHit, titleHit), 5).searchScored(1L, "退订规范", 5);
        assertEquals(2, hits.size());
        assertEquals("退订规范", hits.get(0).entry().getTitle());
        assertEquals(9, hits.get(0).score());
        assertEquals("无关标题", hits.get(1).entry().getTitle());
        assertEquals(2, hits.get(1).score());
    }

    @Test
    void disabledEntriesExcluded() {
        KnowledgeEntity enabled = entry("触达退订", "退订客户禁止触达", List.of("退订"), true);
        KnowledgeEntity disabled = entry("触达退订（停用）", "退订客户禁止触达", List.of("退订"), false);
        List<KnowledgeBaseService.KnowledgeHit> hits =
                svc(List.of(enabled, disabled), 5).searchScored(1L, "触达退订", 5);
        assertEquals(1, hits.size());
        assertEquals("触达退订", hits.get(0).entry().getTitle());
    }

    @Test
    void topKTruncates() {
        KnowledgeEntity e1 = entry("触达规范一", "退订", List.of("触达"), true);
        KnowledgeEntity e2 = entry("触达规范二", "退订", List.of("触达"), true);
        KnowledgeEntity e3 = entry("触达规范三", "退订", List.of("触达"), true);
        List<KnowledgeBaseService.KnowledgeHit> hits =
                svc(List.of(e1, e2, e3), 3).searchScored(1L, "触达规范", 3);
        assertEquals(3, hits.size()); // 3 条齐命中，topK=3 全返回
        List<KnowledgeBaseService.KnowledgeHit> capped =
                svc(List.of(e1, e2, e3), 1).searchScored(1L, "触达规范", 1);
        assertEquals(1, capped.size());
    }

    @Test
    void blankQueryAndNoiseFiltered() {
        KnowledgeEntity contentOnly = entry("无关标题", "退订", List.of(), true); // 仅内容命中 1 词 → +1 < MIN_SCORE(2)
        KnowledgeBaseService svc = svc(List.of(contentOnly), 5);
        assertTrue(svc.searchScored(1L, null, 5).isEmpty());
        assertTrue(svc.searchScored(1L, "  ", 5).isEmpty());
        assertTrue(svc.searchScored(1L, "退订", 0).isEmpty());
        // 内容单词命中低于阈值，被过滤（防"如何/什么"共性词噪声）
        assertTrue(svc.searchScored(1L, "退订", 5).isEmpty());
        // 多词内容命中可入选（≥2 个独立词）
        KnowledgeEntity twoTerms = entry("无关标题", "退订 规范", List.of(), true);
        assertEquals(1, svc(List.of(twoTerms), 5).searchScored(1L, "退订规范", 5).size());
    }
}