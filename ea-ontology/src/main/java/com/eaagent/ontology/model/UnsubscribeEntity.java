package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** unsubscribe 表（平台级全局退订总表：customer_key 哈希，UNIQUE (customer_key, channel) 全局生效）。 */
@Data

@TableName("unsubscribe")
public class UnsubscribeEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_CUSTOMER_KEY = "customer_key";
    public static final String COL_CHANNEL = "channel";
    public static final String COL_REASON = "reason";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;       // 登记租户（归属审计，非隔离键）
    private String customerKey;  // hash(phone|email|openid)，不含明文
    private String channel;
    private String reason;
    private Instant createdAt;
}