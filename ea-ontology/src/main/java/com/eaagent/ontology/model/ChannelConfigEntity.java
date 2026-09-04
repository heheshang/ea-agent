package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** channel_config 表（config_encrypted = 信封加密密文，明文不落库；callback_secret 同）。 */
@Data

@TableName(value = "channel_config", autoResultMap = true)
public class ChannelConfigEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_CHANNEL = "channel";
    public static final String COL_CONFIG_ENCRYPTED = "config_encrypted";
    public static final String COL_ENABLED = "enabled";
    public static final String COL_FREQUENCY_LIMIT = "frequency_limit";
    public static final String COL_CALLBACK_SECRET = "callback_secret";
    public static final String COL_CREATED_AT = "created_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String channel;                  // sms|email|wechat|push|console
    private String configEncrypted;          // 信封加密密文（§9.3）
    private Boolean enabled;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> frequencyLimit;   // jsonb {max_per_day, quiet_hours}
    private String callbackSecret;           // 信封加密密文（§9.3）
    private Instant createdAt;
}