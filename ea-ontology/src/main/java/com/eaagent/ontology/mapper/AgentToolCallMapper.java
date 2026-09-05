package com.eaagent.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.ontology.model.AgentToolCallEntity;
import org.apache.ibatis.annotations.Mapper;

/** agent_tool_call 明细表（调用链回放/审计）。 */
@Mapper
public interface AgentToolCallMapper extends BaseMapper<AgentToolCallEntity> {
}