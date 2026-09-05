package com.eaagent.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * 通用文本工具：日志截断、驼峰/下划线命名互转、从「JSON 对象或 Java map toString」中按键取原始值。
 * 各处重复实现收敛于此（DRY）：truncate 曾散落于 action/engine/service/tool 共 7 处，
 * toSnake/toCamel 散落于 RuleEngine/ObjectApiService/AgentToolRegistry，
 * 解析「applyAction/callFunction 入参取动作/函数名」散落于 RunStatsMiddleware/AgentStatsService/AgentscopeAgentEngine。
 */
public final class Texts {
    private Texts() {
    }

    /**
     * 长文本截断：null → ""；超过 limit 截为 limit 字符并追加省略号。日志防敏感/膨胀。
     */
    public static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    /**
     * SHA-256 十六进制摘要（UTF-8，小写；演示种子退订键与运行期退订校验共用，
     * 收敛自 SendTouchAction/SeedDataInitializer 双份私有实现）。
     */
    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 驼峰 → 下划线（camelCase → camel_case）。 */
    public static String toSnake(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 下划线 → 驼峰（snake_case → snakeCase）。 */
    public static String toCamel(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                up = true;
            } else if (up) {
                sb.append(Character.toUpperCase(c));
                up = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 从入参取指定键的值（参数可能是 JSON 对象、Java map 或其 toString）：
     * 优先 Map 直取 → JSON 解析取键 → Java map toString 正则回退（{key=v, …}）。
     * 找不到返回 null。applyAction → "action"、callFunction → "name" 共用此语义。
     */
    public static String firstValue(Object input, String key) {
        if (input == null) {
            return null;
        }
        if (input instanceof Map<?, ?> m) {
            Object v = m.get(key);
            return v == null ? null : String.valueOf(v);
        }
        String s = String.valueOf(input).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtils.readMap(s);
            Object v = map.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        } catch (Exception ignored) {
            // 非 JSON（Java map toString），走下方正则
        }
        int i = s.indexOf(key + "=");
        if (i >= 0) {
            String rest = s.substring(i + key.length() + 1).trim();
            int j = rest.indexOf(',');
            if (j > 0) {
                return rest.substring(0, j).trim();
            }
            j = rest.indexOf('}');
            if (j > 0) {
                return rest.substring(0, j).trim();
            }
        }
        return null;
    }
}
