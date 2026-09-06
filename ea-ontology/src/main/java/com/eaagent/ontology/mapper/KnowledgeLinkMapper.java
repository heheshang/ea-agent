package com.eaagent.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.ontology.model.KnowledgeLinkEntity;
import org.apache.ibatis.annotations.Mapper;

/** knowledge_link 关系边表（V15）：图谱类型化边；查询/写入均由 Service 侧按 tenant_id 显式限定。 */
@Mapper
public interface KnowledgeLinkMapper extends BaseMapper<KnowledgeLinkEntity> {
}