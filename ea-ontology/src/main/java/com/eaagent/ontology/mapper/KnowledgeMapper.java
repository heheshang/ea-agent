package com.eaagent.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.ontology.model.KnowledgeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeMapper extends BaseMapper<KnowledgeEntity> {

    /**
     * pgvector 余弦检索：租户启用且已嵌入的条目按相似度升序取 {@code limit} 条（排名由 SQL 完成）。
     * 返回行含 distance（余弦距离，越小越近；tags::text 转字符串防 PGobject）；
     * Service 侧再按相似度阈值过滤弱命中并截断到 topK。
     * {@code lifecycle} 非 null 时按生命周期过滤（对话注入传 active——被取代/废弃条目按构造排除）；
     * null 查全部（管理端试检索可看全部状态）。
     */
    @Select("""
            <script>
            SELECT id, tenant_id, title, content, tags::text AS tags, enabled,
                   record_type, lifecycle, supersedes_id, created_at, updated_at,
                   (embedding &lt;=> #{queryVec}::vector) AS distance
            FROM knowledge
            WHERE tenant_id = #{tenantId}
              AND enabled = true
              AND embedding IS NOT NULL
              <if test="lifecycle != null">
              AND lifecycle = #{lifecycle}
              </if>
            ORDER BY (embedding &lt;=> #{queryVec}::vector)
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> searchSimilar(@Param("tenantId") Long tenantId,
                                            @Param("queryVec") String queryVec,
                                            @Param("limit") int limit,
                                            @Param("lifecycle") String lifecycle);

    /** 维护条目向量（create/update 后即时写入，启动回填补齐存量）；显式 ::vector 转换字符串字面量。 */
    @Update("UPDATE knowledge SET embedding = #{embedding}::vector WHERE id = #{id}")
    int updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);
}