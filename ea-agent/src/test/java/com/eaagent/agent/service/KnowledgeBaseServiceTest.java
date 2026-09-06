package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.api.dto.KnowledgeWriteRequest;
import com.eaagent.common.BizException;
import com.eaagent.ontology.mapper.KnowledgeLinkMapper;
import com.eaagent.ontology.mapper.KnowledgeMapper;
import com.eaagent.ontology.model.KnowledgeEntity;
import com.eaagent.ontology.model.KnowledgeGraphResponse;
import com.eaagent.ontology.model.KnowledgeLinkEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        return new KnowledgeBaseService(mapper, mock(KnowledgeLinkMapper.class), topK);
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
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt(), any())).thenReturn(List.of(
                row(1L, "触达退订规范", List.of("退订", "触达"), 0.4),  // 余弦 0.6 + 词命中 → 选中
                row(3L, "流失预警", List.of("流失"), 0.2),            // 余弦 0.8 但无共同词 → 词校验剔除（哈希碰撞）
                row(2L, "频道频控", List.of("频控"), 0.95)));     // 余弦 0.05 → 低于阈值即截断
        List<KnowledgeBaseService.KnowledgeHit> hits = svc(mapper, 3).searchScored(1L, "触达退订", 3);
        assertEquals(List.of(1L), hits.stream().map(h -> h.entry().getId()).toList());
        assertEquals(0.6, hits.get(0).score());
        assertEquals(List.of("退订", "触达"), hits.get(0).entry().getTags()); // tags::text 反序列化
        assertEquals("触达退订规范", hits.get(0).entry().getTitle());
        // SQL 参数：租户、向量字面量、取回余量 = topK × 5、生命周期 = active（现行条目注入）
        verify(mapper).searchSimilar(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(
                        s -> s.startsWith("[") && s.endsWith("]") && s.split(",").length == KnowledgeBaseService.EMBEDDING_DIM),
                org.mockito.ArgumentMatchers.eq(15),
                org.mockito.ArgumentMatchers.eq(KnowledgeEntity.LIFE_ACTIVE));
    }

    @Test
    void searchScoredTruncatesToTopK() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt(), any())).thenReturn(List.of(
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
        verify(mapper, never()).searchSimilar(any(Long.class), anyString(), anyInt(), any());
    }

    @Test
    void singleLatinCharQueryMatchesLiteralTerm() {
        // id5 用户数据形态：content="t"、tags=["test","t"] → q=t 按字面词检索命中
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt(), any())).thenReturn(List.of(
                row(5L, "tuiding", List.of("test", "t"), 0.0)));
        List<KnowledgeBaseService.KnowledgeHit> hits = svc(mapper, 3).searchScored(1L, "t", 3);
        assertEquals(List.of(5L), hits.stream().map(h -> h.entry().getId()).toList());
        assertEquals(1.0, hits.get(0).score());
        assertEquals(List.of("test", "t"), hits.get(0).entry().getTags());
        // 单字符进入特征哈希：向量非零、SQL 参数与常规检索一致（active 现行过滤）
        verify(mapper).searchSimilar(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(s -> {
                    String[] vals = s.substring(1, s.length() - 1).split(",");
                    return vals.length == KnowledgeBaseService.EMBEDDING_DIM
                            && java.util.Arrays.stream(vals).anyMatch(v -> Math.abs(Double.parseDouble(v)) > 1e-9);
                }),
                org.mockito.ArgumentMatchers.eq(15),
                org.mockito.ArgumentMatchers.eq(KnowledgeEntity.LIFE_ACTIVE));
    }

    // ---------- V14 本体化：类型/生命周期/取代链 ----------

    @Test
    void searchScoredDefaultsToActiveLifecycle() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt(), any())).thenReturn(List.of(
                row(1L, "触达退订规范", List.of("退订"), 0.1)));
        svc(mapper, 3).searchScored(1L, "退订", 3);
        // 默认 active-only：第 4 参传现行生命周期（被取代/废弃由 SQL 按构造排除）
        verify(mapper).searchSimilar(eq(1L), anyString(), anyInt(), eq(KnowledgeEntity.LIFE_ACTIVE));
    }

    @Test
    void searchScoredIncludeInactivePassesNullLifecycle() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.searchSimilar(any(Long.class), anyString(), anyInt(), any())).thenReturn(List.of());
        svc(mapper, 3).searchScored(1L, "退订", 3, true);
        verify(mapper).searchSimilar(eq(1L), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void createRejectsUnknownRecordTypeAndLifecycle() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
        req.setTitle("x");
        req.setContent("y");
        req.setRecordType("鬼扯类型");
        assertThrows(BizException.class, () -> svc(mapper, 3).create(1L, req));
        req.setRecordType(null);
        req.setLifecycle("半死不活");
        assertThrows(BizException.class, () -> svc(mapper, 3).create(1L, req));
    }

    @Test
    void createDefaultsToRuleActive() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.insert(any(KnowledgeEntity.class))).thenAnswer(inv -> {
            KnowledgeEntity e = inv.getArgument(0);
            e.setId(7L);
            return 1;
        });
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
        req.setTitle("触达频控");
        req.setContent("每人每天最多 1 次触达");
        svc(mapper, 3).create(1L, req);
        verify(mapper).insert(argThat((KnowledgeEntity e) ->
                KnowledgeEntity.TYPE_RULE.equals(e.getRecordType())
                        && KnowledgeEntity.LIFE_ACTIVE.equals(e.getLifecycle())));
    }

    @Test
    void createWithSupersedesMarksTargetSuperseded() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity target = entry("旧触达规范", "旧条款", List.of(), true);
        target.setId(5L);
        when(mapper.selectOne(any())).thenReturn(target);
        when(mapper.insert(any(KnowledgeEntity.class))).thenAnswer(inv -> {
            KnowledgeEntity e = inv.getArgument(0);
            e.setId(7L);
            return 1;
        });
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
        req.setTitle("新触达规范");
        req.setContent("新条款");
        req.setSupersedesId(5L);
        svc(mapper, 3).create(1L, req);
        // 新条目写取代边；目标行在同事务内置 superseded
        verify(mapper).insert(argThat((KnowledgeEntity e) -> Long.valueOf(5L).equals(e.getSupersedesId())));
        ArgumentCaptor<UpdateWrapper<KnowledgeEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(KnowledgeEntity.LIFE_SUPERSEDED));
    }

    @Test
    void createSupersedingNonActiveTargetRejected() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity target = entry("废弃旧条目", "旧条款", List.of(), true);
        target.setId(5L);
        target.setLifecycle(KnowledgeEntity.LIFE_OBSOLETE);
        when(mapper.selectOne(any())).thenReturn(target);
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
        req.setTitle("新条目");
        req.setContent("内容");
        req.setSupersedesId(5L);
        assertThrows(BizException.class, () -> svc(mapper, 3).create(1L, req));
        verify(mapper, never()).insert(any(KnowledgeEntity.class));
    }

    @Test
    void updateRejectsSupersedingItself() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity existing = entry("自身", "内容", List.of(), true);
        existing.setId(7L);
        when(mapper.selectOne(any())).thenReturn(existing);
        KnowledgeWriteRequest upd = new KnowledgeWriteRequest();
        upd.setSupersedesId(7L);
        assertThrows(BizException.class, () -> svc(mapper, 3).update(1L, 7L, upd));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void updateSwappingSupersedeTargetRestoresOldTargetWhenUnsuperseded() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity existing = entry("现行规范", "新条款", List.of(), true);
        existing.setId(7L);
        existing.setSupersedesId(3L); // 原取代目标 3
        KnowledgeEntity newTarget = entry("另一条规则", "条款", List.of(), true);
        newTarget.setId(4L);
        when(mapper.selectOne(any())).thenReturn(existing, newTarget, existing);
        when(mapper.selectCount(any())).thenReturn(0L); // 目标 3 已无 active 条目取代
        when(mapper.update(any(), any())).thenReturn(1);
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
        req.setSupersedesId(4L); // 换成取代 4
        svc(mapper, 3).update(1L, 7L, req);
        // 三次 update：新目标 4 置 superseded + 原目标 3 恢复 active（restore）+ 本条主更新
        ArgumentCaptor<UpdateWrapper<KnowledgeEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper, times(3)).update(isNull(), captor.capture());
        List<String> lifecycleValues = captor.getAllValues().stream()
                .flatMap(uw -> uw.getParamNameValuePairs().values().stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        assertTrue(lifecycleValues.contains(KnowledgeEntity.LIFE_SUPERSEDED));
        assertTrue(lifecycleValues.contains(KnowledgeEntity.LIFE_ACTIVE));
    }

    @Test
    void updateKeepingSupersedeTargetDoesNotRestoreOldTarget() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity existing = entry("现行", "条款", List.of(), true);
        existing.setId(7L);
        existing.setSupersedesId(3L);
        KnowledgeEntity target = entry("旧规则", "旧条款", List.of(), true);
        target.setId(3L);
        when(mapper.selectOne(any())).thenReturn(existing, target, existing);
        when(mapper.update(any(), any())).thenReturn(1);
        KnowledgeWriteRequest req = new KnowledgeWriteRequest();
req.setSupersedesId(3L); // 保持原取代目标
        svc(mapper, 3).update(1L, 7L, req);
        // 两次 update：目标翻转（3 → superseded）+ 本条主更新；无 restoreIfUnsuperseded 的 active 恢复
        ArgumentCaptor<UpdateWrapper<KnowledgeEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        List<String> lifecycleValues = captor.getAllValues().stream()
                .flatMap(uw -> uw.getParamNameValuePairs().values().stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        assertEquals(List.of(KnowledgeEntity.LIFE_SUPERSEDED), lifecycleValues);
    }

    @Test
    void traceReturnsChainOldestToNewest() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeEntity v1 = entry("触达规范 v1", "第一版", List.of(), true);
        v1.setId(1L);
        KnowledgeEntity v2 = entry("触达规范 v2", "第二版", List.of(), true);
        v2.setId(2L);
        v2.setSupersedesId(1L);
        KnowledgeEntity v3 = entry("触达规范 v3", "第三版", List.of(), true);
        v3.setId(3L);
        v3.setSupersedesId(2L);
        // 按 SQL 片段 + 首个参数值分发：by-supersedes 查询返回后继/null，by-id 返回对应版本
        when(mapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<KnowledgeEntity> qw = inv.getArgument(0);
            String segment = qw.getSqlSegment(); // 先生成占位符（副作用：填充参数表）
            // 首个 eq 列的值固定落在 MPGENVAL1（MyBatis-Plus 占位符命名，探针实证）
            Object firstVal = qw.getParamNameValuePairs().get("MPGENVAL1");
            if (segment.contains(KnowledgeEntity.COL_SUPERSEDES_ID)) {
                long sid = ((Number) firstVal).longValue();
                return sid == 2L ? v3 : null;
            }
            long id = ((Number) firstVal).longValue();
            return switch ((int) id) {
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                default -> null;
            };
        });
        List<KnowledgeEntity> chain = svc(mapper, 3).trace(1L, 2L);
        assertEquals(List.of(1L, 2L, 3L), chain.stream().map(KnowledgeEntity::getId).toList());
    }

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

    // ---------- 图谱（V15） ----------

    @Test
    void graphMergesSupersedesAndTypedLinks() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeLinkMapper linkMapper = mock(KnowledgeLinkMapper.class);
        KnowledgeEntity v1 = entry("规则v1", "旧规则", List.of(), true);
        v1.setId(1L);
        v1.setLifecycle("superseded");
        KnowledgeEntity v2 = entry("规则v2", "新规则", List.of(), true);
        v2.setId(2L);
        v2.setLifecycle("active");
        v2.setSupersedesId(1L);
        when(mapper.selectList(any())).thenReturn(List.of(v1, v2));
        KnowledgeLinkEntity link = new KnowledgeLinkEntity();
        link.setId(9L);
        link.setTenantId(1L);
        link.setSourceId(1L);
        link.setTargetId(2L);
        link.setRelationType("related");
        when(linkMapper.selectList(any())).thenReturn(List.of(link));

        KnowledgeGraphResponse g = new KnowledgeBaseService(mapper, linkMapper, 3).graph(1L);

        assertEquals(2, g.getNodes().size());
        assertEquals(2, g.getEdges().size());
        KnowledgeGraphResponse.Edge sup = g.getEdges().get(0);
        assertEquals(2L, sup.getSource());
        assertEquals(1L, sup.getTarget());
        assertEquals("supersedes", sup.getRelation());
        assertNull(sup.getLinkId());
        KnowledgeGraphResponse.Edge rel = g.getEdges().get(1);
        assertEquals(1L, rel.getSource());
        assertEquals(2L, rel.getTarget());
        assertEquals("related", rel.getRelation());
        assertEquals(9L, rel.getLinkId());
    }

    @Test
    void createLinkValidatesEndpointAndDuplicate() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeLinkMapper linkMapper = mock(KnowledgeLinkMapper.class);
        when(mapper.selectOne(any())).thenReturn(entry("存在", "内容", List.of(), true)); // 两端存在
        KnowledgeBaseService svc = new KnowledgeBaseService(mapper, linkMapper, 3);

        assertThrows(BizException.class, () -> svc.createLink(1L, 1L, 1L, "related"));        // 自连
        assertThrows(BizException.class, () -> svc.createLink(1L, 1L, 2L, "supercedes"));     // 非法类型
        when(linkMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> svc.createLink(1L, 1L, 2L, "supports"));       // 已存在
    }

    @Test
    void createLinkNormalizesTypeAndInserts() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeLinkMapper linkMapper = mock(KnowledgeLinkMapper.class);
        when(mapper.selectOne(any())).thenReturn(entry("存在", "内容", List.of(), true));
        when(linkMapper.selectCount(any())).thenReturn(0L);
        KnowledgeBaseService svc = new KnowledgeBaseService(mapper, linkMapper, 3);

        KnowledgeLinkEntity created = svc.createLink(1L, 3L, 5L, " SUPPORTS ");

        assertEquals("supports", created.getRelationType());
        assertEquals(3L, created.getSourceId());
        assertEquals(5L, created.getTargetId());
        assertEquals(1L, created.getTenantId());
        verify(linkMapper).insert(any(KnowledgeLinkEntity.class));
    }

    @Test
    void deleteLinkRequiresTenantMatch() {
        KnowledgeLinkMapper linkMapper = mock(KnowledgeLinkMapper.class);
        when(linkMapper.selectOne(any())).thenReturn(null);
        KnowledgeBaseService svc = new KnowledgeBaseService(mock(KnowledgeMapper.class), linkMapper, 3);

        assertThrows(BizException.class, () -> svc.deleteLink(1L, 9L));
        verify(linkMapper, never()).deleteById((java.io.Serializable) any());
    }
}