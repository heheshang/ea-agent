package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** tenant_user 表（9.1 bcrypt；复合 FK 目标 (tenant_id, id)）。 */
@Data

@TableName("tenant_user")
public class TenantUserEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_LOGIN_NAME = "login_name";
    public static final String COL_NAME = "name";
    public static final String COL_PASSWORD_HASH = "password_hash";
    public static final String COL_ROLE = "role";
    public static final String COL_STATUS = "status";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String loginName;
    private String name;
    private String passwordHash;
    private String role;           // OPERATOR|REVIEWER|ADMIN
    private String status;
    private Instant createdAt;
}