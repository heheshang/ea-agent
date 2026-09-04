package com.eaagent.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.ontology.model.TenantEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<TenantEntity> {
}