package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.api.dto.KnowledgeWriteRequest;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.PageResult;
import com.eaagent.common.Texts;
import com.eaagent.ontology.mapper.KnowledgeMapper;
import com.eaagent.ontology.model.KnowledgeEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库服务（ea-agent）：租户级知识条目的管理 + 对话检索注入。
 *
 * <p>检索策略：Postgres pgvector 余弦检索（V7 迁移新增 embedding vector(256) 列 + HNSW 索引）。
 * query 与条目均经 {@link #tokenize} 切词（CJK 字符二元组 / latin 词整体小写），再按特征哈希
 * （hashing trick）向量化：词项以标题 +3 / 标签 +2 / 内容 +1 加权（与旧内存打分权重一致），
 * 双哈希映射到 {@link #EMBEDDING_DIM} 维（h1 定维、h2 定号）带符号累加后 L2 归一；查询向量每词项权重 1。
 * 排名由 SQL 完成（embedding &lt;=&gt; 余弦距离升序，LIMIT topK×{@link #CANDIDATE_FACTOR} 取回余量），
 * Service 侧再按 {@link #MIN_COSINE} 阈值过滤弱命中（对齐旧 MIN_SCORE=2 语义：标题/标签命中必过阈，
 * 内容通常需 ≥2 个独立词），命中得分为余弦相似度（0..1）。
 * 向量在 create/update 后即时维护，启动时幂等回填存量（embedding IS NULL，含 Seed 直插行）。
 */
@Service
public class KnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 特征向量维度：须与 V7 迁移 knowledge.embedding vector(256) 一致。 */
    static final int EMBEDDING_DIM = 256;
    /** 入选最低余弦相似度：单字段 1 个共性词（短查询）贡献约 0.05~0.1，标题/标签命中显著更高，过滤"如何/什么"类噪声。 */
    static final double MIN_COSINE = 0.1;
    /** SQL 取回余量倍率：先取 topK×N，阈值过滤后截断，避免批量相似条目挤占候选。 */
    static final int CANDIDATE_FACTOR = 5;
    /** 注入条数上限默认值（可经 ea.knowledge.top-k 覆盖）。 */
    static final int DEFAULT_TOP_K = 3;

    private final KnowledgeMapper knowledgeMapper;
    private final int topK;

    public KnowledgeBaseService(KnowledgeMapper knowledgeMapper,
                                @Value("${ea.knowledge.top-k:3}") int topK) {
        this.knowledgeMapper = knowledgeMapper;
        this.topK = Math.max(topK, 0);
    }

    // ---------- 管理 ----------

    /** 分页列表：keyword 命中标题/内容/标签（tags::text 模糊），按更新时间倒序。 */
    public PageResult<KnowledgeEntity> list(Long tenantId, String keyword, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        QueryWrapper<KnowledgeEntity> qw = new QueryWrapper<KnowledgeEntity>()
                .eq(KnowledgeEntity.COL_TENANT_ID, tenantId);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like(KnowledgeEntity.COL_TITLE, k)
                    .or().like(KnowledgeEntity.COL_CONTENT, k)
                    .or().like("tags::text", k));
        }
        long total = knowledgeMapper.selectCount(qw);
        qw.orderByDesc(KnowledgeEntity.COL_UPDATED_AT)
                .last("LIMIT " + s + " OFFSET " + (long) (p - 1) * s);
        return new PageResult<>(knowledgeMapper.selectList(qw), null, total);
    }

    /** 单条（租户内校验）；不存在抛 E-12007。 */
    public KnowledgeEntity get(Long tenantId, Long id) {
        KnowledgeEntity e = knowledgeMapper.selectOne(new QueryWrapper<KnowledgeEntity>()
                .eq(KnowledgeEntity.COL_ID, id)
                .eq(KnowledgeEntity.COL_TENANT_ID, tenantId)
                .last("LIMIT 1"));
        if (e == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        return e;
    }

    public KnowledgeEntity create(Long tenantId, KnowledgeWriteRequest req) {
        String title = req.getTitle() == null ? null : req.getTitle().trim();
        String content = req.getContent();
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "标题与内容不能为空");
        }
        KnowledgeEntity e = new KnowledgeEntity();
        e.setTenantId(tenantId);
        e.setTitle(title);
        e.setContent(content);
        e.setTags(req.getTags() == null ? List.of() : req.getTags());
        e.setEnabled(req.getEnabled() == null || req.getEnabled());
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        knowledgeMapper.insert(e);
        refreshEmbedding(e);
        log.info("knowledge created id={} tenantId={} title={}", e.getId(), tenantId, truncate(title, 60));
        return e;
    }

    /** 更新：传入覆盖、缺失保留；合并后标题/内容须非空（靶向更新，避免旧快照覆写统计列）。 */
    public KnowledgeEntity update(Long tenantId, Long id, KnowledgeWriteRequest req) {
        get(tenantId, id); // 存在性 + 租户校验
        String title = req.getTitle() == null ? null : req.getTitle().trim();
        String content = req.getContent();
        if (title != null && title.isBlank() || content != null && content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "标题与内容不能为空");
        }
        UpdateWrapper<KnowledgeEntity> uw = new UpdateWrapper<KnowledgeEntity>()
                .eq(KnowledgeEntity.COL_ID, id)
                .eq(KnowledgeEntity.COL_TENANT_ID, tenantId);
        if (title != null) {
            uw.set(KnowledgeEntity.COL_TITLE, title);
        }
        if (content != null) {
            uw.set(KnowledgeEntity.COL_CONTENT, content);
        }
        if (req.getTags() != null) {
            uw.set(KnowledgeEntity.COL_TAGS, req.getTags(),
                    "typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler");
        }
        if (req.getEnabled() != null) {
            uw.set(KnowledgeEntity.COL_ENABLED, req.getEnabled());
        }
        uw.set(KnowledgeEntity.COL_UPDATED_AT, Instant.now());
        knowledgeMapper.update(null, uw);
        KnowledgeEntity updated = get(tenantId, id);
        refreshEmbedding(updated);
        log.info("knowledge updated id={} tenantId={}", id, tenantId);
        return updated;
    }

    /** 物理删除（表无 deleted 列；先查存在性否则 E-12007）。 */
    public void delete(Long tenantId, Long id) {
        get(tenantId, id);
        knowledgeMapper.delete(new QueryWrapper<KnowledgeEntity>()
                .eq(KnowledgeEntity.COL_ID, id)
                .eq(KnowledgeEntity.COL_TENANT_ID, tenantId));
        log.info("knowledge deleted id={} tenantId={}", id, tenantId);
    }

    // ---------- 检索 ----------

    /** Agent 对话注入用：取配置 topK 条命中条目（按相关度）。 */
    public List<KnowledgeEntity> search(Long tenantId, String query) {
        return searchScored(tenantId, query, this.topK).stream().map(KnowledgeHit::entry).toList();
    }

    /** 检索（可调 topK 并暴露得分，供知识库管理页"试检索"预览）：pgvector 余弦 topK + 双闸过滤。 */
    public List<KnowledgeHit> searchScored(Long tenantId, String query, int topK) {
        int k = Math.max(topK, 0);
        if (tenantId == null || query == null || query.isBlank() || k == 0) {
            return List.of();
        }
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        String queryVec = toVectorLiteral(embedQuery(query));
        List<Map<String, Object>> rows =
                knowledgeMapper.searchSimilar(tenantId, queryVec, Math.min(k * CANDIDATE_FACTOR, 100));
        List<KnowledgeHit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double cosine = 1.0 - ((Number) row.get("distance")).doubleValue();
            if (cosine < MIN_COSINE) {
                break; // 距离升序 → 越后越不相似，首个低于阈值即可截断
            }
            KnowledgeEntity e = toEntity(row);
            if (score(e, terms) < 1) {
                continue; // 无任何共同词：哈希碰撞噪声（如"灰度发布"撞上无关查询），沿用旧"无命中不入选"语义
            }
            hits.add(new KnowledgeHit(e, round4(Math.min(Math.max(cosine, 0), 1))));
        }
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    // ---------- 特征向量（hashing trick，确定性、零外部依赖） ----------

    /** 词项 → 加权频次：标题 +3、标签 +2、内容 +1（与旧打分权重一致；逐字段去重、跨字段累加）。 */
    static Map<String, Integer> weightedTerms(KnowledgeEntity e) {
        Map<String, Integer> counts = new HashMap<>();
        countTerms(counts, tokenize(e.getTitle() == null ? "" : e.getTitle()), 3);
        String tags = e.getTags() == null ? "" : String.join(" ", e.getTags());
        countTerms(counts, tokenize(tags), 2);
        countTerms(counts, tokenize(e.getContent() == null ? "" : e.getContent()), 1);
        return counts;
    }

    private static void countTerms(Map<String, Integer> counts, List<String> terms, int weight) {
        for (String t : terms) {
            counts.merge(t, weight, Integer::sum);
        }
    }

    /** 特征哈希向量：term → (h1 定维、h2 定号) 加权累加，L2 归一；输入经 L2 归一后可直接点积求余弦。 */
    static float[] embed(Map<String, Integer> weighted) {
        float[] v = new float[EMBEDDING_DIM];
        for (Map.Entry<String, Integer> en : weighted.entrySet()) {
            String t = en.getKey();
            int idx = Math.floorMod(t.hashCode(), EMBEDDING_DIM);
            int sign = ((t + "\u0001").hashCode() & 1) == 0 ? 1 : -1;
            v[idx] += sign * en.getValue();
        }
        float norm = 0f;
        for (float f : v) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-8f) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    /** 条目 → 向量。 */
    static float[] embed(KnowledgeEntity e) {
        return embed(weightedTerms(e));
    }

    /** 查询 → 查询向量（每词项权重 1）。 */
    static float[] embedQuery(String query) {
        Map<String, Integer> w = new HashMap<>();
        for (String t : queryTerms(query)) {
            w.put(t, 1);
        }
        return embed(w);
    }

    /**
     * 查询词项：常规切词（tokenize）；整体为单 latin 字符时按字面词回退
     * （如 content="t" 的条目可被 q=t 命中，见 id5 用户数据）；CJK 单字在中文语料命中面过宽，保持短路。
     */
    static List<String> queryTerms(String query) {
        List<String> terms = tokenize(query);
        if (!terms.isEmpty()) {
            return terms;
        }
        String single = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (single.length() == 1 && !isCjk(single)) {
            return List.of(single);
        }
        return List.of();
    }

    /** float[] → pgvector 字面量（[..] 方括号，pgvector 0.7+ 支持）。 */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.8f", v[i]));
        }
        return sb.append(']').toString();
    }

    /** 余弦相似度（两向量均已 L2 归一 → 点积）。 */
    static double cosine(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            s += (double) a[i] * b[i];
        }
        return s;
    }

    /** 计算并写入条目向量（create/update 后调用；幂等，仅覆盖本行）。 */
    private void refreshEmbedding(KnowledgeEntity e) {
        if (e.getId() == null) {
            return;
        }
        knowledgeMapper.updateEmbedding(e.getId(), toVectorLiteral(embed(e)));
    }

    private static KnowledgeEntity toEntity(Map<String, Object> row) {
        KnowledgeEntity e = new KnowledgeEntity();
        e.setId(((Number) row.get("id")).longValue());
        e.setTenantId(((Number) row.get("tenant_id")).longValue());
        e.setTitle((String) row.get("title"));
        e.setContent((String) row.get("content"));
        e.setTags(parseTags((String) row.get("tags")));
        e.setEnabled((Boolean) row.get("enabled"));
        e.setCreatedAt(toInstant(row.get("created_at")));
        e.setUpdatedAt(toInstant(row.get("updated_at")));
        return e;
    }

    private static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.warn("knowledge tags 解析失败: {}", json);
            return List.of();
        }
    }

    private static Instant toInstant(Object v) {
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        return v instanceof Instant i ? i : null;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    // ---------- 向量回填 ----------

    /**
     * 幂等补齐缺失向量（embedding IS NULL）：V7 迁移前的存量行、Seed 直插行。
     * 启动时（@PostConstruct）与 Seed 落库后调用；表小逐条计算写入，开销可忽略。
     */
    public void backfillEmbeddings() {
        List<KnowledgeEntity> missing = knowledgeMapper.selectList(
                new QueryWrapper<KnowledgeEntity>().isNull("embedding").last("LIMIT 1000"));
        if (missing.isEmpty()) {
            return;
        }
        for (KnowledgeEntity e : missing) {
            refreshEmbedding(e);
        }
        log.info("knowledge embedding 回填: 条数={}", missing.size());
    }

    @PostConstruct
    void ensureEmbeddingsAtStartup() {
        try {
            backfillEmbeddings();
        } catch (Exception ex) {
            log.error("knowledge embedding 启动回填失败（缺失向量条目在检索中被过滤，不阻断启动）", ex);
        }
    }

    // ---------- 切词 ----------

    /**
     * 切词：空白/标点/符号切分；CJK 段按字符二元组（"退订规范" → 退订/订规/规范），
     * latin/数字段整体小写。长度不足的去噪（单字、单字母）。
     */
    static List<String> tokenize(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : query.toLowerCase(Locale.ROOT).split("[\\s\\p{P}\\p{S}]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (isCjk(part)) {
                if (part.length() == 1) {
                    continue;
                }
                for (int i = 0; i + 1 < part.length(); i++) {
                    terms.add(part.substring(i, i + 2));
                }
            } else if (part.length() >= 2) {
                terms.add(part);
            }
        }
        return new ArrayList<>(terms);
    }

    private static boolean isCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                return true;
            }
        }
        return false;
    }

    /**
     * 词覆盖校验（入选闸门，非排序依据）：标题 +3、标签 +2、内容 +1，逐 term 子串包含累加。
     * 仅用于把 pgvector 候选中的哈希碰撞噪声（向量近但无共同词）剔除，等价旧"无命中不入选"语义；
     * 排序完全由 SQL 余弦距离决定。
     */
    static int score(KnowledgeEntity e, List<String> terms) {
        String title = e.getTitle() == null ? "" : e.getTitle().toLowerCase(Locale.ROOT);
        String tags = e.getTags() == null ? ""
                : String.join(" ", e.getTags()).toLowerCase(Locale.ROOT);
        String content = e.getContent() == null ? "" : e.getContent().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String t : terms) {
            if (title.contains(t)) {
                score += 3;
            }
            if (tags.contains(t)) {
                score += 2;
            }
            if (content.contains(t)) {
                score += 1;
            }
        }
        return score;
    }

    private static String truncate(String s, int max) {
        return Texts.truncate(s, max);
    }

    /** 检索命中：条目 + 相似度得分（0..1）。 */
    public record KnowledgeHit(KnowledgeEntity entry, double score) {
    }
}