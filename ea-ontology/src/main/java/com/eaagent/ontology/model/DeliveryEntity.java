package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** delivery 表（触达实例 + 幂等双唯一 + 灰度/AB 审计；回执回调幂等 (tenant_id, channel_msg_id)）。 */
@Data

@TableName("delivery")
public class DeliveryEntity {
    public static final String COL_ID = "id";
    public static final String COL_REQUEST_ID = "request_id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_CAMPAIGN_ID = "campaign_id";
    public static final String COL_CUSTOMER_ID = "customer_id";
    public static final String COL_CHANNEL = "channel";
    public static final String COL_TEMPLATE_ID = "template_id";
    public static final String COL_CHANNEL_MSG_ID = "channel_msg_id";
    public static final String COL_GRAY_HIT = "gray_hit";
    public static final String COL_AB_GROUP = "ab_group";
    public static final String COL_STATUS = "status";
    public static final String COL_ERROR = "error";
    public static final String COL_ATTEMPT = "attempt";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;      // 幂等键（发送）
    private Long tenantId;
    private Long campaignId;
    private Long customerId;
    private String channel;
    private Long templateId;
    private String channelMsgId;   // 通道侧消息 ID（回执关联）
    private Boolean grayHit;       // 灰度抽样命中审计
    private String abGroup;        // NULL=非实验|CONTROL|TREATMENT_A/B/C
    private String status;         // PENDING|SENT|DELIVERED|BOUNCED|FAILED|UNSUBSCRIBED
    private String error;
    private Integer attempt;
    private Instant createdAt;
    private Instant updatedAt;
}