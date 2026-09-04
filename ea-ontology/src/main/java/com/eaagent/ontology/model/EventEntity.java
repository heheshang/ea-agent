package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** event 表（业务事件持久对象；幂等 (tenant_id, dedup_key)；EA-Bus 双写）。 */
@Data

@TableName(value = "event", autoResultMap = true)
public class EventEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_CUSTOMER_ID = "customer_id";
    public static final String COL_EVENT_TYPE = "event_type";
    public static final String COL_PAYLOAD = "payload";
    public static final String COL_DEDUP_KEY = "dedup_key";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long customerId;
    private String eventType;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> payload;   // jsonb
    private String dedupKey;
    private Instant createdAt;
}