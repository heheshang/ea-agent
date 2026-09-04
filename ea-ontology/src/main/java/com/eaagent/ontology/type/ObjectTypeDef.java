package com.eaagent.ontology.type;

import java.util.List;

/**
 * 对象类型定义（3.1）：类型名 / 实体类 / 接口能力 / 字段白名单。
 * 内置类型注册表驱动 schema 校验、DSL 字段校验、脱敏视图三处复用；新增类型只加 Def 不改枚举与 Action。
 */
public record ObjectTypeDef(String name, Class<?> entityCls, List<String> interfaces, List<FieldDef> fields) {

    public FieldDef field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst().orElse(null);
    }

    public boolean isQueryable(String fieldName) {
        FieldDef f = field(fieldName);
        return f != null && f.queryable();
    }

    public boolean implementsInterface(String iface) {
        return interfaces.contains(iface);
    }
}