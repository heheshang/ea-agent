package com.eaagent.ontology.function;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;

import java.util.Map;

/** Function 入参解析小工具：缺参/非法类型统一抛 E-10001（工具层已兜底转 {"error"}）。 */
final class FunctionArgs {

    private FunctionArgs() {
    }

    static long requireLong(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, key + " 必填且为整数");
        }
    }

    static String requireString(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, key + " 必填");
        }
        return String.valueOf(v).trim();
    }
}