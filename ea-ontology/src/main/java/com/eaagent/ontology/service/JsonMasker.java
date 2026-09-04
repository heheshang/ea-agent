package com.eaagent.ontology.service;

import com.eaagent.common.MaskUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 递归脱敏视图（9.6 LLM 工具 / API 投影共用）：对象图内 phone/email/wechat_openid 键
 * 逐层掩码；白名单外键不进入输出。
 */
public final class JsonMasker {
    private static final List<String> SENSITIVE_KEYS = List.of("phone", "email", "wechat_openid");

    private JsonMasker() {
    }

    public static Map<String, Object> mask(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> inner) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) inner;
                out.put(e.getKey(), mask(m));
            } else if (v instanceof List<?> list) {
                out.put(e.getKey(), maskList(list));
            } else if (v instanceof String s && SENSITIVE_KEYS.contains(e.getKey())) {
                out.put(e.getKey(), MaskUtils.maskByKey(e.getKey(), s));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private static List<Object> maskList(List<?> list) {
        List<Object> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                out.add(mask(mm));
            } else if (o instanceof List<?> l) {
                out.add(maskList(l));
            } else {
                out.add(o);
            }
        }
        return out;
    }
}