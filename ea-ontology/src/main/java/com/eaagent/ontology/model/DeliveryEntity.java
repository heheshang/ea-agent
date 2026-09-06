package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** delivery 表（触达实例 + 幂等双唯一 + AB 审计；回执回调幂等 (tenant_id, channel_msg_id)）。 */
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
    public static final String COL_STATUS = "status";
    public static final String COL_ERROR = "error";
    public static final String COL_ATTEMPT = "attempt";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    /** status 值（PENDING|SENT|DELIVERED|BOUNCED|FAILED|UNSUBSCRIBED）。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_BOUNCED = "BOUNCED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNSUBSCRIBED = "UNSUBSCRIBED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;      // 幂等键（发送）
    private Long tenantId;
    private Long campaignId;
    private Long customerId;
    private String channel;
    private Long templateId;
    private String channelMsgId;   // 通道侧消息 ID（回执关联）
    private String status;         // PENDING|SENT|DELIVERED|BOUNCED|FAILED|UNSUBSCRIBED
    private String error;
    private Integer attempt;
    private Instant createdAt;
    private Instant updatedAt;
}