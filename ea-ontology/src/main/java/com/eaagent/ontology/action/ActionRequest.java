package com.eaagent.ontology.action;

import java.util.Map;

/**
 * 通用 Action 请求参数容器（3.2 applyAction args）。
 * 各 Action 通过 meta.requiredArgs 声明并做类型/枚举校验（E-13002）。
 */
public record ActionRequest(Map<String, Object> args) {

    public static ActionRequest of(Map<String, Object> args) {
        return new ActionRequest(args == null ? Map.of() : args);
    }

    public Object get(String key) {
        return args.get(key);
    }

    public String getString(String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Long getLong(String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }

    public Integer getInt(String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(v));
    }

    public Boolean getBool(String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object v = args.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> getList(String key) {
        Object v = args.get(key);
        return v instanceof java.util.List ? (java.util.List<Map<String, Object>>) v : null;
    }
}