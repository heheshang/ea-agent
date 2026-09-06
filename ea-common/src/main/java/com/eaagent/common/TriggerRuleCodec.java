package com.eaagent.common;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 触发规则（campaign.trigger_rule jsonb）参数编解码：
 * 契约 {@code {"event_type":"order_placed","window":"1d"}}
 * （详细设计 8.4 / 数据流 3.5）。window 保存时归一为 ISO-8601 Duration
 * （1h→PT1H、30m→PT30M、2d→PT48H、90s→PT1M30S、纯数字按秒），
 * 非法格式在保存路径即抛 BizException（E-13002），避免触发时 Duration.parse 500。
 * cooldown 键随冷却期机制移除：normalize 直接丢弃，不再保留。
 * 消费侧用 parseLooseDuration 兜底存量脏数据。
 */
public final class TriggerRuleCodec {

    /** 宽松时长：纯数字 = 秒；{数字}{s|m|h|d} = 秒/分/时/天；其余按 ISO-8601。 */
    private static final Pattern LOOSE = Pattern.compile("^\\s*(\\d+)\\s*([smhd])\\s*$");
    private static final Pattern PLAIN_SECONDS = Pattern.compile("^\\s*(\\d+)\\s*$");

    private static final int EVENT_TYPE_MAX = 64;   // 对齐 event.event_type varchar(64) 契约

    private TriggerRuleCodec() {
    }

    /** 宽松解析时长：ISO-8601（PT1H）原样；1h/30m/2d/90s/3600 归一解析；非法抛 IllegalArgumentException。 */
    public static Duration parseLooseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("duration is blank");
        }
        String s = raw.trim();
        try {
            return Duration.parse(s);
        } catch (Exception ignored) {
            // fallthrough to loose formats
        }
        Matcher m = LOOSE.matcher(s);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            char unit = m.group(2).charAt(0);
            switch (unit) {
                case 's':
                    return Duration.ofSeconds(n);
                case 'm':
                    return Duration.ofMinutes(n);
                case 'h':
                    return Duration.ofHours(n);
                default:
                    return Duration.ofDays(n);
            }
        }
        if (PLAIN_SECONDS.matcher(s).matches()) {
            return Duration.ofSeconds(Long.parseLong(s));
        }
        throw new IllegalArgumentException("invalid duration: " + raw);
    }

    /**
     * 归一触发规则：输出新 map（含未知键宽容保留）。
     * event_type trim（空白移除、超 64 报错）；window 宽松格式归一 ISO-8601，非法报错。
     * cooldown 键（机制移除）直接丢弃。
     */
    public static Map<String, Object> normalize(Map<String, Object> rule) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rule == null) {
            return out;
        }
        for (Map.Entry<String, Object> en : rule.entrySet()) {
            String key = en.getKey();
            Object val = en.getValue();
            if (val == null) {
                continue;
            }
            String str = String.valueOf(val);
            if (str.isBlank()) {
                continue;   // 空白值 = 移除该键
            }
            switch (key) {
                case "event_type" -> {
                    String t = str.trim();
                    if (t.length() > EVENT_TYPE_MAX) {
                        throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                                "触发规则 event_type 过长（≤" + EVENT_TYPE_MAX + "）: " + t);
                    }
                    out.put(key, t);
                }
                case "cooldown" -> {
                    // 冷却期机制已移除：键直接丢弃（存量由 V11 迁移清理）
                }
                case "window" -> {
                    try {
                        out.put(key, parseLooseDuration(str).toString());
                    } catch (IllegalArgumentException e) {
                        throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                                "触发规则 " + key + " 格式非法（支持 PT1H / 1h / 30m / 2d / 90s）: " + str);
                    }
                }
                default -> out.put(key, val);
            }
        }
        return out;
    }
}