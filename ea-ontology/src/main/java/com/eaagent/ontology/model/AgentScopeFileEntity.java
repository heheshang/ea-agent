package com.eaagent.ontology.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.Instant;

/** agent_scope_file 表（agentscope BaseStore workspace 文件 KV：schema 4.1）。 */
@Data

@TableName(value = "agent_scope_file", autoResultMap = true)
public class AgentScopeFileEntity {
    public static final String COL_ID = "id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_NAMESPACE = "namespace";
    public static final String COL_ITEM_KEY = "item_key";
    public static final String COL_VALUE = "value";
    public static final String COL_VERSION = "version";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** namespace 段 join 串（以 \u001F 分隔，段间分隔符不能用 \0——PG text 禁 NUL）。 */
    private String namespace;
    /** workspace 文件相对路径（相对 route 根）。 */
    private String itemKey;
    /** 文件内容 jsonb：{"content","encoding","created_at","modified_at"}。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private java.util.Map<String, Object> value;
    /** 乐观版本号（1 起，put/putIfVersion 成功自增，CAS 依据）。 */
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
}