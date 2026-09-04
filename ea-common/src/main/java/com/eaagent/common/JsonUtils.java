package com.eaagent.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;

/**
 * Jackson 单例封装（省略号处理/时间序列化统一）。
 */
public final class JsonUtils {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 宽松解析器：容忍单引号键值/未引号键/尾逗号（LLM 常输出 Python 风格 dict 文本）。 */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonUtils() {
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    public static <T> T read(String json, Class<T> cls) {
        try {
            return MAPPER.readValue(json, cls);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json deserialize failed", e);
        }
    }

    public static Map<String, Object> toMap(Object o) {
        return MAPPER.convertValue(o, new TypeReference<Map<String, Object>>() {
        });
    }

    public static <T> T convert(Object o, Class<T> cls) {
        return MAPPER.convertValue(o, cls);
    }

    public static Map<String, Object> readMap(String json) {
        return read(json, new TypeReference<Map<String, Object>>() {
        });
    }

    /** 宽松解析 Map：先按标准 JSON，失败后容忍单引号/未引号键/尾逗号（LLM 工具参数防抖）。 */
    public static Map<String, Object> readMapLenient(String json) {
        try {
            return readMap(json);
        } catch (IllegalStateException e) {
            try {
                return LENIENT.readValue(json, new TypeReference<Map<String, Object>>() {
                });
            } catch (JsonProcessingException e2) {
                throw new IllegalStateException("json deserialize failed (lenient)", e2);
            }
        }
    }

    public static <T> T read(String json, TypeReference<T> ref) {
        try {
            return MAPPER.readValue(json, ref);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json deserialize failed", e);
        }
    }
}