package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** audience_member 表（仅 STATIC 人群；DYNAMIC 成员实时派生不落表 —— 3.3）。 */
@Data

@TableName("audience_member")
public class AudienceMemberEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_AUDIENCE_ID = "audience_id";
    public static final String COL_CUSTOMER_ID = "customer_id";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long audienceId;
    private Long customerId;
    private Instant createdAt;
}