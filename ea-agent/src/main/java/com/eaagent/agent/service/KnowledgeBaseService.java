package com.eaagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.eaagent.api.dto.KnowledgeWriteRequest;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.PageResult;
import com.eaagent.ontology.mapper.KnowledgeMapper;
import com.eaagent.ontology.model.KnowledgeEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 知识库服务（ea-agent）：租户级知识条目的管理 + 对话检索注入。
 *
 * <p>检索策略：内存关键词加权打分（确定性、零外部依赖、中文友好）——
 * query 按空白/标点/符号切词：CJK 段生成字符二元组（长度 &gt;= 2，单字丢弃），latin 段整体小写（长度 &gt;= 2）；
 * 每条目得分 = 标题命中 +3 + 标签命中 +2 + 内容命中 +1（子串包含、逐 term 累加）；
 * 总分 &gt;= {@link #MIN_SCORE} 才入选（去通用词噪声），按分倒序、同分按更新时间倒序，取 topK。
 * 后续如需向量检索（embedding/pgvector），在同一注入点替换本实现即可，调用方无感知。
 */
@Service
public class KnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** 检索加载上限：租户启用条目过多时的保护（一般租户 KB 远小于此）。 */
    static final int MAX_LOAD = 1000;
    /** 入选最低分：命中标题/标签即过，或内容至少 2 个独立词（过滤"如何/什么"等共性词噪声）。 */
    static final int MIN_SCORE = 2;
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
        log.info("knowledge updated id={} tenantId={}", id, tenantId);
        return get(tenantId, id);
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

    /** 检索（可调 topK 并暴露得分，供知识库管理页"试检索"预览）。 */
    public List<KnowledgeHit> searchScored(Long tenantId, String query, int topK) {
        int k = Math.max(topK, 0);
        if (tenantId == null || query == null || query.isBlank() || k == 0) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<KnowledgeEntity> all = knowledgeMapper.selectList(new QueryWrapper<KnowledgeEntity>()
                .eq(KnowledgeEntity.COL_TENANT_ID, tenantId)
                .eq(KnowledgeEntity.COL_ENABLED, true)
                .orderByDesc(KnowledgeEntity.COL_UPDATED_AT)
                .last("LIMIT " + MAX_LOAD));
        if (all.isEmpty()) {
            return List.of();
        }
        List<KnowledgeHit> hits = new ArrayList<>();
        for (KnowledgeEntity e : all) {
            int score = score(e, terms);
            if (score >= MIN_SCORE) {
                hits.add(new KnowledgeHit(e, score));
            }
        }
        hits.sort(Comparator.comparingInt((KnowledgeHit h) -> h.score).reversed()
                .thenComparing(h -> h.entry().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        return hits.subList(0, Math.min(k, hits.size()));
    }

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

    /** 加权打分：标题 +3、标签 +2、内容 +1，逐 term 子串包含累加。 */
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
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 检索命中：条目 + 得分。 */
    public record KnowledgeHit(KnowledgeEntity entry, int score) {
    }
}