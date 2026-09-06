package com.eaagent.agent.service;

import com.eaagent.api.dto.KnowledgeWriteRequest;
import com.eaagent.ontology.mapper.KnowledgeMapper;
import com.eaagent.ontology.model.KnowledgeEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseService 检索单测（pgvector 余弦检索，确定性可测）：
 * 覆盖切词、特征向量（维度/确定性/标题加权）、SQL 结果映射与阈值过滤、topK 截断、
 * create/update 后向量写回、启动回填补齐、空/空白查询短路。
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

    /** pgvector 检索行（等价 searchSimilar 的 Map 行，distance = 余弦距离）。 */
    private static Map<String, Object> row(long id, String title, List<String> tags, double distance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tenant_id", 1L);
        m.put("title", title);
        m.put("content", "内容" + title);
        m.put("tags", "[\"" + String.join("\",\"", tags) + "\"]");
        m.put("enabled", true);
        m.put("created_at", java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        m.put("updated_at", java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        m.put("distance", distance);
        return m;
    }

    private static KnowledgeBaseService svc(KnowledgeMapper mapper, int topK) {
        return new KnowledgeBaseService(mapper, topK);
    }

    // ---------- 切词 ----------

    @Test
    void tokenizeCjkBigramsAndLatin() {
        assertEquals(List.of("退订", "订规", "规范"), KnowledgeBaseService.tokenize("退订规范"));
        assertEquals(List.of("gray", "release"), KnowledgeBaseService.tokenize("gray release"));
        assertTrue(KnowledgeBaseService.tokenize("退").isEmpty());
        assertTrue(KnowledgeBaseService.tokenize("a !").isEmpty());
        // 混合段：CJK 分支逐字符二元组（含 latin 子串）
        assertEquals(List.of("ab", "b测", "测试"), KnowledgeBaseService.tokenize("AB测试"));
    }

    // ---------- 特征向量 ----------

    @Test
    void embedDeterministicAndDim() {
        KnowledgeEntity e = entry("触达退订规范", "触达前必须核对客户退订状态", List.of("退订", "触达"), true);
        float[] v1 = KnowledgeBaseService.embed(e);
        float[] v2 = KnowledgeBaseService.embed(e);
        assertEquals(KnowledgeBaseService.EMBEDDING_DIM, v1.length);
        assertArrayEquals(v1, v2, 0f);
        // 查询向量：词项权重 1
        assertEquals(3, KnowledgeBaseService.embedQuery("触达退订").length - 253); // 仅维度断言示意
        assertArrayEquals(KnowledgeBaseService.embedQuery("触达退订"),
                KnowledgeBaseService.embedQuery("触达退订"), 0f);
        // 字面量：方括号分隔、长度与向量一致
        String lit = KnowledgeBaseService.toVectorLiteral(v1);
        assertTrue(lit.startsWith("["));
        assertTrue(lit.endsWith("]"));
        assertEquals(KnowledgeBaseService.EMBEDDING_DIM, lit.split(",").length);
    }

    @Test
    void titleWeightedEmbedOutranksContentEmbed() {
        // 同文本：一条在标题（×3），一条仅内容（×1）→ 与查询向量余弦应前者更高（与旧打分权重语义一致）
        float[] q = KnowledgeBaseService.embedQuery("退订规范");
        float[] titleV = KnowledgeBaseService.embed(entry("退订规范", "", List.of(), true));
        float[] contentV = KnowledgeBaseService.embed(entry("", "退订规范", List.of(), true));
        double titleCos = KnowledgeBaseService.cosine(q, titleV);
        double contentCos = KnowledgeBaseService.cosine(q, contentV);
        assertTrue(titleCos > contentCos, "标题加权（×3）命中应高于纯内容（×1）");
        assertTrue(contentCos > 0);
    }

    // ---------- 检索（mock pgvector SQL 结果） ----------

    @Test
    void searchScoredMapsSqlRowsAndFiltersByThreshold() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt())).thenReturn(List.of(
                row(1L, "触达退订规范", List.of("退订", "触达"), 0.4),  // 余弦 0.6 + 词命中 → 选中
                row(3L, "流失预警", List.of("流失"), 0.2),            // 余弦 0.8 但无共同词 → 词校验剔除（哈希碰撞）
                row(2L, "频道频控", List.of("频控"), 0.95)));     // 余弦 0.05 → 低于阈值即截断
        List<KnowledgeBaseService.KnowledgeHit> hits = svc(mapper, 3).searchScored(1L, "触达退订", 3);
        assertEquals(List.of(1L), hits.stream().map(h -> h.entry().getId()).toList());
        assertEquals(0.6, hits.get(0).score());
        assertEquals(List.of("退订", "触达"), hits.get(0).entry().getTags()); // tags::text 反序列化
        assertEquals("触达退订规范", hits.get(0).entry().getTitle());
        // SQL 参数：租户、向量字面量、取回余量 = topK × 5
        verify(mapper).searchSimilar(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(
                        s -> s.startsWith("[") && s.endsWith("]") && s.split(",").length == KnowledgeBaseService.EMBEDDING_DIM),
                org.mockito.ArgumentMatchers.eq(15));
    }

    @Test
    void searchScoredTruncatesToTopK() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt())).thenReturn(List.of(
                row(3L, "查询c", List.of(), 0.3),
                row(2L, "查询b", List.of(), 0.2),
                row(1L, "查询a", List.of(), 0.1)));
        List<KnowledgeBaseService.KnowledgeHit> hits = svc(mapper, 3).searchScored(1L, "查询", 2);
        assertEquals(2, hits.size());
        assertEquals(3L, hits.get(0).entry().getId()); // 距离升序 → 余弦降序，最相似在前
    }

    @Test
    void blankOrEmptyQuerySkipsMapper() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeBaseService s = svc(mapper, 3);
        assertEquals(0, s.searchScored(1L, "   ", 3).size());
        assertEquals(0, s.searchScored(1L, "退", 3).size()); // 单 CJK 字命中面过宽，短路
        assertEquals(0, s.searchScored(null, "退订", 3).size());
        assertEquals(0, s.searchScored(1L, "退订", 0).size());
        verify(mapper, never()).searchSimilar(any(Long.class), anyString(), anyInt());
    }

    @Test
    void singleLatinCharQueryMatchesLiteralTerm() {
        // id5 用户数据形态：content="t"、tags=["test","t"] → q=t 按字面词检索命中
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt())).thenReturn(List.of(
                row(5L, "tuiding", List.of("test", "t"), 0.0)));
        List<KnowledgeBaseService.KnowledgeHit> hits = svc(mapper, 3).searchScored(1L, "t", 3);
        assertEquals(List.of(5L), hits.stream().map(h -> h.entry().getId()).toList());
        assertEquals(1.0, hits.get(0).score());
        assertEquals(List.of("test", "t"), hits.get(0).entry().getTags());
        // 单字符进入特征哈希：向量非零、SQL 参数与常规检索一致
        verify(mapper).searchSimilar(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(s -> {
                    String[] vals = s.substring(1, s.length() - 1).split(",");
                    return vals.length == KnowledgeBaseService.EMBEDDING_DIM
                            && java.util.Arrays.stream(vals).anyMatch(v -> Math.abs(Double.parseDouble(v)) > 1e-9);
                }),
                org.mockito.ArgumentMatchers.eq(15));
    }

    // ---------- 向量写入维护 ----------

    @Test
    void createWritesEmbedding() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.insert(any(KnowledgeEntity.class))).thenAnswer(inv -> {
            KnowledgeEntity e = inv.getArgument(0);
            e.setId(7L);
            return 1;
        });
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
        req.setTitle("触达退订规范");
        req.setContent("触达前必须核对客户退订状态");
        req.setTags(List.of("退订", "触达"));
        svc(mapper, 3).create(1L, req);
        verify(mapper).updateEmbedding(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(s -> s.startsWith("[") && s.endsWith("]")));
    }

    @Test
    void updateRefreshesEmbedding() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity existing = entry("旧标题", "旧内容", List.of("旧标签"), true);
        existing.setId(7L);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(any(), any())).thenReturn(1);
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
        req.setTitle("新标题");
        req.setContent("新内容");
        svc(mapper, 3).update(1L, 7L, req);
        verify(mapper).updateEmbedding(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(s -> s.startsWith("[") && s.endsWith("]")));
    }

    @Test
    void backfillEmbedsRowsWithNullEmbedding() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity e1 = entry("退订规范", "退订语义", List.of("退订"), true);
        e1.setId(1L);
        KnowledgeEntity e2 = entry("灰度发布", "灰度分桶", List.of("灰度"), true);
        e2.setId(2L);
        when(mapper.selectList(any())).thenReturn(List.of(e1, e2));
        svc(mapper, 3).backfillEmbeddings();
        verify(mapper).updateEmbedding(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.argThat(s -> s.startsWith("[")));
        verify(mapper).updateEmbedding(org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.argThat(s -> s.startsWith("[")));
    }
}