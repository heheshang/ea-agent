package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** customer 表（核心业务实体；attributes jsonb 画像，GIN 索引）。 */
@Data

@TableName(value = "customer", autoResultMap = true)
public class CustomerEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_EXTERNAL_ID = "external_id";
    public static final String COL_PHONE = "phone";
    public static final String COL_EMAIL = "email";
    public static final String COL_WECHAT_OPENID = "wechat_openid";
    public static final String COL_ATTRIBUTES = "attributes";
    public static final String COL_TAGS = "tags";
    public static final String COL_STATUS = "status";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    /** status 值（ACTIVE|INACTIVE）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String externalId;
    private String phone;
    private String email;
    private String wechatOpenid;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> attributes;   // jsonb
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.List<String> tags;               // jsonb 数组（用户标签）
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}