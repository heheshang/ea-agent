package com.eaagent.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.AudienceMemberMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.AudienceMemberEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.rule.RuleEngine;
import com.eaagent.ontology.type.TypeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 人群成员解析（3.3）：DYNAMIC = 规则实时派生（RuleEngine → customer）；
 * STATIC = audience_member 显式成员。统一返回全量客户（发送量级基线内直查）。
 */
@Service
@RequiredArgsConstructor
public class AudienceResolver {
    private final RuleEngine ruleEngine;
    private final CustomerMapper customerMapper;
    private final AudienceMemberMapper memberMapper;

    public List<CustomerEntity> resolve(Long tenantId, AudienceEntity audience) {
        if ("STATIC".equals(audience.getMode())) {
            List<AudienceMemberEntity> members = memberMapper.selectList(
                    new QueryWrapper<AudienceMemberEntity>()
                            .eq(AudienceMemberEntity.COL_TENANT_ID, tenantId).eq(AudienceMemberEntity.COL_AUDIENCE_ID, audience.getId()));
            List<Long> ids = members.stream().map(AudienceMemberEntity::getCustomerId).toList();
            if (ids.isEmpty()) {
                return List.of();
            }
            return customerMapper.selectList(new QueryWrapper<CustomerEntity>()
                    .eq(CustomerEntity.COL_TENANT_ID, tenantId).in(CustomerEntity.COL_ID, ids));
        }
        // DYNAMIC：DSL 编译 + 租户过滤
        QueryWrapper<?> w = ruleEngine.compile(TypeRegistry.get("customer"), audience.getRule());
        w.eq(CustomerEntity.COL_TENANT_ID, tenantId);
        @SuppressWarnings("unchecked")
        QueryWrapper<CustomerEntity> cw = (QueryWrapper<CustomerEntity>) w;
        List<CustomerEntity> all = new ArrayList<>();
        // 分批防超长 IN/全表；基线直接全量
        all.addAll(customerMapper.selectList(cw));
        return all;
    }
}