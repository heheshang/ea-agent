package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 追加式人工模板批注/审核记录，只保存模板原文快照，不携带聊天正文。 */
@Data
@TableName("template_review")
public class TemplateReviewEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_TEMPLATE_ID = "template_id";
    public static final String COL_CREATED_AT = "created_at";

    public static final String DECISION_COMMENT = "COMMENT";
    public static final String DECISION_APPROVE = "APPROVE";
    public static final String DECISION_REJECT = "REJECT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long templateId;
    private long templateVersion;
    private Long chatId;
    private Long userId;
    private String decision;
    private String comment;
    private String title;
    private String content;
    private Instant createdAt;
}
