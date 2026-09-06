package com.eaagent.app.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.MaskUtils;
import com.eaagent.common.PageResult;
import com.eaagent.common.PageToken;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 活动投递查询（/api/campaigns/{id}/deliveries 的数据源）：
 * 租户隔离的投递日志游标分页 + 客户联系信息富化（phone/email 掩码）。
 * Controller 不直调 mapper，统一经本 service。
 */
@Service
public class CampaignDeliveryService {

    private final DeliveryMapper deliveryMapper;
    private final CustomerMapper customerMapper;

    public CampaignDeliveryService(DeliveryMapper deliveryMapper, CustomerMapper customerMapper) {
        this.deliveryMapper = deliveryMapper;
        this.customerMapper = customerMapper;
    }

    /**
     * 游标分页投递日志（offset 语义同对象 API）：created_at, id 倒序稳定排序；
     * 本页涉及客户批量查询一次（避免 N+1），联系信息按敏感列掩码投影。
     */
    public PageResult<Map<String, Object>> pageDeliveries(long tenantId, Long campaignId, String pageToken, Integer limit) {
        int size = limit == null ? 20 : Math.min(Math.max(limit, 1), 200);
        long total = deliveryMapper.selectCount(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_TENANT_ID, tenantId)
                .eq(DeliveryEntity.COL_CAMPAIGN_ID, campaignId));
        long offset = PageToken.decode(pageToken);
        List<DeliveryEntity> rows = deliveryMapper.selectList(new QueryWrapper<DeliveryEntity>()
                .select(DeliveryEntity.COL_ID, DeliveryEntity.COL_CUSTOMER_ID, DeliveryEntity.COL_CHANNEL,
                        DeliveryEntity.COL_TEMPLATE_ID, DeliveryEntity.COL_CHANNEL_MSG_ID,
                        DeliveryEntity.COL_STATUS, DeliveryEntity.COL_ERROR,
                        DeliveryEntity.COL_ATTEMPT, DeliveryEntity.COL_WORKFLOW_NODE,
                        DeliveryEntity.COL_CREATED_AT, DeliveryEntity.COL_UPDATED_AT)
                .eq(DeliveryEntity.COL_TENANT_ID, tenantId)
                .eq(DeliveryEntity.COL_CAMPAIGN_ID, campaignId)
                .orderByDesc(DeliveryEntity.COL_CREATED_AT)
                .orderByDesc(DeliveryEntity.COL_ID)
                .last("LIMIT " + size + " OFFSET " + offset));
        List<Long> customerIds = rows.stream().map(DeliveryEntity::getCustomerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, CustomerEntity> customers = new HashMap<>();
        if (!customerIds.isEmpty()) {
            customerMapper.selectList(new QueryWrapper<CustomerEntity>()
                            .select(CustomerEntity.COL_ID, CustomerEntity.COL_EXTERNAL_ID, CustomerEntity.COL_PHONE,
                                    CustomerEntity.COL_EMAIL, CustomerEntity.COL_ATTRIBUTES, CustomerEntity.COL_STATUS)
                            .eq(CustomerEntity.COL_TENANT_ID, tenantId)
                            .in(CustomerEntity.COL_ID, customerIds))
                    .forEach(c -> customers.put(c.getId(), c));
        }
        List<Map<String, Object>> items = rows.stream().map(d -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", d.getId());
            out.put("customer_id", d.getCustomerId());
            out.put("channel", d.getChannel());
            out.put("template_id", d.getTemplateId());
            out.put("channel_msg_id", d.getChannelMsgId());
            out.put("status", d.getStatus());
            out.put("error", d.getError());
            out.put("attempt", d.getAttempt());
            out.put("workflow_node", d.getWorkflowNode());
            out.put("created_at", d.getCreatedAt() == null ? null : d.getCreatedAt().toString());
            out.put("updated_at", d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString());
            CustomerEntity c = customers.get(d.getCustomerId());
            if (c != null) {
                out.put("customer_external_id", c.getExternalId());
                out.put("customer_phone", MaskUtils.maskByKey("phone", c.getPhone()));
                out.put("customer_email", MaskUtils.maskByKey("email", c.getEmail()));
                out.put("customer_name", c.getAttributes() == null ? null
                        : String.valueOf(c.getAttributes().getOrDefault("name", "")));
                out.put("customer_status", c.getStatus());
            }
            return out;
        }).toList();
        long next = offset + items.size();
        return new PageResult<>(items, next < total ? PageToken.encode(next) : null, total);
    }
}