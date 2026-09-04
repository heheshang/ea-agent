package com.eaagent.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaagent.ontology.model.CustomerEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CustomerMapper extends BaseMapper<CustomerEntity> {
}