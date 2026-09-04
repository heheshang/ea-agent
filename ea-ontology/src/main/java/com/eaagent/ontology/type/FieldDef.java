package com.eaagent.ontology.type;

/**
 * 对象字段定义（3.1.1）：白名单 + 敏感标记。
 * queryable=false 的字段禁止出现在 DSL 与投影（如 channel_config 凭据列物理层杜绝，无 FieldDef）。
 * sensitive=true 的字段在工具返回与 API 投影时掩码（138****1234），明文仅存在于 Action 服务端调用栈。
 */
public record FieldDef(String name, FieldType type, boolean queryable, boolean sensitive) {
    public static FieldDef q(String name, FieldType type) {
        return new FieldDef(name, type, true, false);
    }

    public static FieldDef s(String name, FieldType type) {
        return new FieldDef(name, type, true, true);
    }
}