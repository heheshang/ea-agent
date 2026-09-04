package com.eaagent.ontology.function;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.model.CustomerEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * channelPreference：渠道偏好（4.2 Call Function，决策咨询）。
 * 自原有同名工具收编：读取画像 attributes.preferred_channel；租户归属校验缺失即拒绝。
 */
@Component
public class ChannelPreferenceFunction implements Function {

    private final CustomerMapper customerMapper;

    public ChannelPreferenceFunction(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public String name() {
        return "channelPreference";
    }

    @Override
    public String description() {
        return "渠道偏好：读取客户画像的偏好通道（attributes.preferred_channel）";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("customer_id", Map.of("type", "integer", "description", "客户 ID"));
    }

    @Override
    public Map<String, Object> execute(long tenantId, Map<String, Object> args) {
        long cid = FunctionArgs.requireLong(args, "customer_id");
        CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                .eq(CustomerEntity.COL_TENANT_ID, tenantId).eq(CustomerEntity.COL_ID, cid));
        if (c == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "customer not found: " + cid);
        }
        Map<String, Object> attrs = c.getAttributes() == null ? Map.of() : c.getAttributes();
        return Map.of("customer_id", cid, "preferred_channel", attrs.getOrDefault("preferred_channel", "console"));
    }
}