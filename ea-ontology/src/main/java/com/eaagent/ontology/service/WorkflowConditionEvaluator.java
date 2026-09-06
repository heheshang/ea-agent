package com.eaagent.ontology.service;

import com.eaagent.ontology.model.CustomerEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流节点条件求值器（workflow 节点 condition，风格对齐 TemplateRoutingService 条件）：
 * {@code {"event":{...}, "customer":{...}, "prev":{...}}}，三组 AND；组内 attr→规则（标量=等于，或 {op,value}）。
 * - event.attr：取值自触发事件 payload；
 * - customer.attr：客户 attributes 优先，回退实体字段（external_id/phone/email/wechat_openid/status/tags/id）；
 * - prev.<node_id>：该客户在前一节点的最近投递状态（执行器按 (campaign, customer, workflow_node) 关联 delivery）。
 * 条件为 null/空 → 恒真（无条件触发）。求值失败（取值缺失/数值比较非法）一律 false，不抛。
 */
public final class WorkflowConditionEvaluator {

    /** 条件分组（顶层键）。 */
    public static final Set<String> GROUPS = Set.of("event", "customer", "prev");
    /** 支持的操作符（TemplateRoutingService 4 种 + 数值比较 4 种）。 */
    public static final String OPS = "eq|neq|contains|is_set|gt|gte|lt|lte";
    public static final Set<String> OP_SET = Set.of("eq", "neq", "contains", "is_set", "gt", "gte", "lt", "lte");

    private WorkflowConditionEvaluator() {
    }

    /**
     * @param condition    节点条件（可 null → 恒真）
     * @param eventPayload 触发事件 payload（可 null）
     * @param customer     目标客户（可 null：需求值一律 false）
     * @param prevStatus   该客户各前序节点的最近投递状态 {node_id: status}（可 null）
     */
    public static boolean evaluate(Map<String, Object> condition, Map<String, Object> eventPayload,
                                   CustomerEntity customer, Map<String, String> prevStatus) {
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        for (String group : GROUPS) {
            Object rules = condition.get(group);
            if (rules == null) {
                continue;
            }
            if (!(rules instanceof Map<?, ?> gm)) {
                return false;
            }
            for (Map.Entry<?, ?> en : gm.entrySet()) {
                String attr = String.valueOf(en.getKey());
                Object rule = en.getValue();
                Object actual = lookup(group, attr, eventPayload, customer, prevStatus);
                if (!match(actual, rule)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object lookup(String group, String attr, Map<String, Object> eventPayload,
                                 CustomerEntity customer, Map<String, String> prevStatus) {
        switch (group) {
            case "event" -> {
                return eventPayload == null ? null : eventPayload.get(attr);
            }
            case "prev" -> {
                if (prevStatus == null) {
                    return null;
                }
                return prevStatus.get(attr);
            }
            default -> {
                if (customer == null) {
                    return null;
                }
                Map<String, Object> attrs = customer.getAttributes();
                if (attrs != null && attrs.containsKey(attr)) {
                    return attrs.get(attr);
                }
                return switch (attr) {
                    case "id" -> customer.getId();
                    case "external_id" -> customer.getExternalId();
                    case "phone" -> customer.getPhone();
                    case "email" -> customer.getEmail();
                    case "wechat_openid" -> customer.getWechatOpenid();
                    case "status" -> customer.getStatus();
                    case "tags" -> customer.getTags();
                    case "name" -> customer.getExternalId();
                    default -> null;
                };
            }
        }
    }

    /** 规则匹配：标量 → eq；{op,value} → 显式操作符。取值缺失时 eq/contains 为 false，is_set 为 false。 */
    private static boolean match(Object actual, Object rule) {
        if (rule instanceof Map<?, ?> m) {
            String op = m.get("op") == null ? "eq" : String.valueOf(m.get("op"));
            Object expected = m.get("value");
            return matchOp(op, actual, expected);
        }
        return looseEquals(actual, rule);
    }

    private static boolean matchOp(String op, Object actual, Object expected) {
        switch (op) {
            case "eq" -> {
                return looseEquals(actual, expected);
            }
            case "neq" -> {
                return !looseEquals(actual, expected);
            }
            case "contains" -> {
                return contains(actual, expected);
            }
            case "is_set" -> {
                return actual != null && !String.valueOf(actual).isBlank();
            }
            case "gt", "gte", "lt", "lte" -> {
                return compare(op, actual, expected);
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean compare(String op, Object actual, Object expected) {
        if (actual == null) {
            return false;
        }
        Double a = toDouble(actual);
        Double e = toDouble(expected);
        if (a == null || e == null) {
            // 非数值（如时间戳字符串）：退化为字符串序比较
            String s1 = String.valueOf(actual);
            String s2 = String.valueOf(expected);
            int c = s1.compareTo(s2);
            return switch (op) {
                case "gt" -> c > 0;
                case "gte" -> c >= 0;
                case "lt" -> c < 0;
                default -> c <= 0;
            };
        }
        int c = Double.compare(a, e);
        return switch (op) {
            case "gt" -> c > 0;
            case "gte" -> c >= 0;
            case "lt" -> c < 0;
            default -> c <= 0;
        };
    }

    private static Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 宽松相等：数值 5 == "5"、true == "true"。 */
    private static boolean looseEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        if (a instanceof Number || b instanceof Number) {
            Double da = toDouble(a);
            Double db = toDouble(b);
            if (da != null && db != null) {
                return Double.compare(da, db) == 0;
            }
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /** 包含：字符串子串，或集合/数组元素（与 TemplateRoutingService 同语义）。 */
    private static boolean contains(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual instanceof String s) {
            return s.contains(String.valueOf(expected));
        }
        if (actual instanceof List<?> list) {
            for (Object o : list) {
                if (looseEquals(o, expected)) {
                    return true;
                }
            }
            return false;
        }
        if (actual instanceof Set<?> set) {
            for (Object o : set) {
                if (looseEquals(o, expected)) {
                    return true;
                }
            }
            return false;
        }
        return String.valueOf(actual).contains(String.valueOf(expected));
    }
}