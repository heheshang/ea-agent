package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** campaign 表（触发规则 jsonb；复合 FK → audience/template）。 */
@Data

@TableName(value = "campaign", autoResultMap = true)
public class CampaignEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_NAME = "name";
    public static final String COL_AUDIENCE_ID = "audience_id";
    public static final String COL_CHANNEL = "channel";
    public static final String COL_TEMPLATE_ID = "template_id";
    public static final String COL_SCHEDULE = "schedule";
    public static final String COL_CRON = "cron";
    public static final String COL_TEMPLATE_ROUTING = "template_routing";
    public static final String COL_OWNER_ID = "owner_id";
    public static final String COL_TRIGGER_RULE = "trigger_rule";
    public static final String COL_AUDIENCE_SNAPSHOT = "audience_snapshot";
    public static final String COL_STATUS = "status";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    /** status 值（DRAFT|SCHEDULED|RUNNING|PAUSED|FINISHED|FAILED）。 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_FINISHED = "FINISHED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private Long audienceId;
    private String channel;            // sms|email|wechat|push|console
    private Long templateId;
    private Instant schedule;          // 一次性时间
    private String cron;               // 周期任务
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.List<java.util.Map<String, Object>> templateRouting; // jsonb 规则→模板
    private Long ownerId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> triggerRule;                  // jsonb {event_type, window}
    /** jsonb 人群快照：创建/换人群时固化的圈定成员 {audience_id,audience_name,mode,rule,member_count,customer_ids,snapshot_at}；空=存量待回填。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> audienceSnapshot;
    private String status;             // DRAFT|SCHEDULED|RUNNING|PAUSED|FINISHED|FAILED
    private Instant createdAt;
    private Instant updatedAt;
}