package com.eaagent.ontology.type;

import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.ChannelConfigEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.EventEntity;
import com.eaagent.ontology.model.TemplateEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置对象类型注册表（3.1 / 3.1.1 白名单逐字落地）。
 * 7 内置类型：customer / audience / campaign / template / channel / delivery / event。
 * attributes.* 动态字段对 customer/event 全开放查询（jsonb containment），运行时不做静态校验。
 */
public final class TypeRegistry {
    /** 接口名：可被触达（sendTouch 目标类型）。 */
    public static final String IFACE_TOUCHABLE = "touchable";

    private static final Map<String, ObjectTypeDef> DEFS = new ConcurrentHashMap<>();

    static {
        register(new ObjectTypeDef("customer", CustomerEntity.class,
                List.of(IFACE_TOUCHABLE),
                List.of(
                        FieldDef.q("id", FieldType.NUMBER),
                        FieldDef.q("status", FieldType.ENUM),
                        FieldDef.q("external_id", FieldType.STRING),
                        FieldDef.q("created_at", FieldType.DATETIME),
                        FieldDef.q("updated_at", FieldType.DATETIME),
                        FieldDef.s("phone", FieldType.STRING),
                        FieldDef.s("email", FieldType.STRING),
                        FieldDef.s("wechat_openid", FieldType.STRING))));
        register(new ObjectTypeDef("audience", AudienceEntity.class,
                List.of(),
                List.of(
                        FieldDef.q("id", FieldType.NUMBER),
                        FieldDef.q("name", FieldType.STRING),
                        FieldDef.q("mode", FieldType.ENUM),
                        FieldDef.q("owner_id", FieldType.NUMBER),
                        FieldDef.q("created_at", FieldType.DATETIME))));
        register(new ObjectTypeDef("campaign", CampaignEntity.class,
                List.of(),
                List.of(
                        FieldDef.q("id", FieldType.NUMBER),
                        FieldDef.q("name", FieldType.STRING),
                        FieldDef.q("status", FieldType.ENUM),
                        FieldDef.q("audience_id", FieldType.NUMBER),
                        FieldDef.q("template_id", FieldType.NUMBER),
                        FieldDef.q("channel", FieldType.ENUM),
                        FieldDef.q("schedule", FieldType.DATETIME),
                        FieldDef.q("cron", FieldType.STRING),
                        FieldDef.q("gray_ratio", FieldType.NUMBER),
                        FieldDef.q("ab_mode", FieldType.ENUM),
                        FieldDef.q("ab_split", FieldType.NUMBER),
                        FieldDef.q("owner_id", FieldType.NUMBER),
                        FieldDef.q("created_at", FieldType.DATETIME))));
        register(new ObjectTypeDef("template", TemplateEntity.class,
                List.of(),
                List.of(
                        FieldDef.q("id", FieldType.NUMBER),
                        FieldDef.q("title", FieldType.STRING),
                        FieldDef.q("channel", FieldType.ENUM),
                        FieldDef.q("review_status", FieldType.ENUM),
                        FieldDef.q("created_at", FieldType.DATETIME),
                        FieldDef.s("content", FieldType.STRING))));
        register(new ObjectTypeDef("channel", ChannelConfigEntity.class,
                List.of(),
                List.of(
                        FieldDef.q("id", FieldType.NUMBER),
                        FieldDef.q("channel", FieldType.ENUM),
                        FieldDef.q("enabled", FieldType.BOOLEAN),
                        FieldDef.q("created_at", FieldType.DATETIME))));
        register(new ObjectTypeDef("delivery", DeliveryEntity.class,
                List.of(),
                List.of(
                        FieldDef.q("id", FieldType.NUMBER),
                        FieldDef.q("campaign_id", FieldType.NUMBER),
                        FieldDef.q("customer_id", FieldType.NUMBER),
                        FieldDef.q("channel", FieldType.ENUM),
                        FieldDef.q("status", FieldType.ENUM),
                        FieldDef.q("gray_hit", FieldType.BOOLEAN),
                        FieldDef.q("request_id", FieldType.STRING),
                        FieldDef.q("created_at", FieldType.DATETIME))));
        register(new ObjectTypeDef("event", EventEntity.class,
                List.of(),
                List.of(
                        FieldDef.q("id", FieldType.NUMBER),
                        FieldDef.q("customer_id", FieldType.NUMBER),
                        FieldDef.q("event_type", FieldType.STRING),
                        FieldDef.q("dedup_key", FieldType.STRING),
                        FieldDef.q("created_at", FieldType.DATETIME))));
    }

    private TypeRegistry() {
    }

    private static void register(ObjectTypeDef def) {
        DEFS.put(def.name(), def);
    }

    public static ObjectTypeDef get(String type) {
        ObjectTypeDef def = DEFS.get(type);
        if (def == null) {
            throw new com.eaagent.common.BizException(com.eaagent.common.ErrorCode.TYPE_UNKNOWN);
        }
        return def;
    }

    public static boolean exists(String type) {
        return DEFS.containsKey(type);
    }
}