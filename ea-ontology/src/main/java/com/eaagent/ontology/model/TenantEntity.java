package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** tenant 表。 */
@Data

@TableName("tenant")
public class TenantEntity {
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_DOMAIN = "domain";
    public static final String COL_PLAN = "plan";
    public static final String COL_STATUS = "status";
    public static final String COL_QUOTA = "quota";
    public static final String COL_CREATED_AT = "created_at";

    /** status 值（ACTIVE|DISABLED）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String domain;
    private String plan;
    private String status;         // ACTIVE|DISABLED
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String quota;          // jsonb
    private Instant createdAt;
}