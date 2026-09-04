package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** audience 表（DYNAMIC=规则派生 / STATIC=成员表，CHECK 互斥）。 */
@Data

@TableName("audience")
public class AudienceEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_NAME = "name";
    public static final String COL_MODE = "mode";
    public static final String COL_RULE = "rule";
    public static final String COL_OWNER_ID = "owner_id";
    public static final String COL_STATUS = "status";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String mode;           // DYNAMIC|STATIC
    private String rule;           // DYNAMIC 人群 DSL
    private Long ownerId;
    private String status;
    private Instant createdAt;
}