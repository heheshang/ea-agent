package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** template 表（content 含 var 引用；审核门控：PENDING→APPROVED 才可发送）。 */
@Data

@TableName(value = "template", autoResultMap = true)
public class TemplateEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_CHANNEL = "channel";
    public static final String COL_TITLE = "title";
    public static final String COL_CONTENT = "content";
    public static final String COL_VARS = "vars";
    public static final String COL_REVIEW_STATUS = "review_status";
    public static final String COL_CREATED_AT = "created_at";

    /** review_status 值（DRAFT|PENDING|APPROVED|REJECTED）。 */
    public static final String REVIEW_DRAFT = "DRAFT";
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_APPROVED = "APPROVED";
    public static final String REVIEW_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String channel;
    private String title;
    private String content;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.List<String> vars;      // jsonb
    private String reviewStatus;              // DRAFT|PENDING|APPROVED|REJECTED
    private Instant createdAt;
}