package com.eaagent.ontology.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.model.CustomerEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** updateCustomerState（3.4）：更新客户状态/画像（attributes 深合并）。 */
@Component
public class UpdateCustomerStateAction extends AbstractAction {

    private final CustomerMapper customerMapper;

    public UpdateCustomerStateAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                                     StringRedisTemplate redis, CustomerMapper customerMapper) {
        super(actionLogMapper, idempotencyService, redis);
        this.customerMapper = customerMapper;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("updateCustomerState")
                .description("更新客户状态与画像")
                .requiredArgs(List.of("customer_id"))
                .permissions(List.of("OPERATOR"))
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        Long tenantId = ctx.tenantId();
        CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                .eq(CustomerEntity.COL_TENANT_ID, tenantId).eq(CustomerEntity.COL_ID, req.getLong("customer_id")));
        if (c == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        String status = req.getString("status");
        if (status != null) {
            c.setStatus(status);
        }
        Map<String, Object> attrs = req.getMap("attributes");
        if (attrs != null) {
            Map<String, Object> merged = new HashMap<>();
            if (c.getAttributes() != null) {
                merged.putAll(c.getAttributes());
            }
            merged.putAll(attrs);
            c.setAttributes(merged);
        }
        Object tagsRaw = req.get("tags");
        if (tagsRaw instanceof List<?> tagsIn) {
            List<String> merged = new ArrayList<>();
            if (c.getTags() != null) {
                merged.addAll(c.getTags());
            }
            for (Object o : tagsIn) {
                if (o != null && !merged.contains(o.toString())) {
                    merged.add(o.toString());
                }
            }
            c.setTags(merged);
        }
        c.setUpdatedAt(Instant.now());
        customerMapper.updateById(c);

        Map<String, Object> out = new HashMap<>();
        out.put("customer_id", c.getId());
        out.put("status", c.getStatus());
        return out;
    }
}