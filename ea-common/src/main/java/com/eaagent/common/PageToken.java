package com.eaagent.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Cursor 分页 token：base64(offset)；无效 token 视为从头分页（3.2 契约），不做深校验。
 */
public final class PageToken {
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private PageToken() {
    }

    public static String encode(long offset) {
        return ENC.encodeToString(Long.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(new String(DEC.decode(token), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return 0;
        }
    }
}