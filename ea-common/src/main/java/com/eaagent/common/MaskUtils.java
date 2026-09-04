package com.eaagent.common;

/**
 * 敏感字段掩码（详细设计 3.1.1 / 9.6 LLM 脱敏视图）：手机号 138****1234、邮箱 a***@x.com。
 */
public final class MaskUtils {
    private MaskUtils() {
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? null : "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** 运行时掩码：按键名识别敏感键（phone/email/wechat_openid 及 attributes 内同名键）。 */
    public static String maskByKey(String key, String value) {
        if (value == null) {
            return null;
        }
        return switch (key) {
            case "phone", "wechat_openid" -> maskPhone(value);
            case "email" -> maskEmail(value);
            default -> value;
        };
    }
}