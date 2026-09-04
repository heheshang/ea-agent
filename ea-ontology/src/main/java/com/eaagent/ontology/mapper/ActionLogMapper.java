package com.eaagent.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.ontology.model.ActionLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ActionLogMapper extends BaseMapper<ActionLogEntity> {
}