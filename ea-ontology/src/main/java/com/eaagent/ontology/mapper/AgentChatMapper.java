package com.eaagent.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.ontology.model.AgentChatEntity;
import org.apache.ibatis.annotations.Mapper;

/** agent_chat 表（聊天容器：id + 描述，跨 run 追踪锚点）。 */
@Mapper
public interface AgentChatMapper extends BaseMapper<AgentChatEntity> {
}