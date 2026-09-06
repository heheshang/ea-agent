package com.eaagent.ontology.rule;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.common.Texts;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.EventEntity;
import com.eaagent.ontology.type.FieldDef;
import com.eaagent.ontology.type.FieldType;
import com.eaagent.ontology.type.ObjectTypeDef;
import com.eaagent.ontology.type.TypeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 人群/查询 DSL 引擎（3.2 EBNF）：
 * <pre>
 * expr     := orExpr
 * orExpr   := andExpr (('OR'|'||'|'or') andExpr)*
 * andExpr  := primary (('AND'|'&&'|'and') primary)*
 * primary  := ['NOT'] ('(' expr ')' | predicate)   // 一元 NOT 否定（LLM 常写 `and not (…)`）
 * predicate:= path op value
 * path     := ident ('.' ident)*
 * op       := '=='|'='|'!='|'<'|'<='|'>'|'>='|'IN'|'NOT IN'|'CONTAINS'|'LIKE'|'BETWEEN'|'EXISTS'
 *             （大小写不敏感，LLM 常见小写变体均兼容；单等号归一为 '=='）
 * value    := '\'' string '\'' | '"' string '"' | number | bool | '[' value (',' value)* ']' | ident
 * </pre>
 * 编译产物为参数化 QueryWrapper（值全部 ? 绑定；列名来自白名单常量，杜绝注入）。
 * 白名单越界抛 E-12003；类型未知抛 E-12004。
 */
public final class RuleEngine {

    // ---- AST ----
    private sealed interface Node permits AndNode, OrNode, NotNode, PredNode {
    }
    private record AndNode(List<Node> children) implements Node {
    }
    private record OrNode(List<Node> children) implements Node {
    }
    private record NotNode(Node child) implements Node {
    }
    private record PredNode(String path, String op, Object value) implements Node {
    }

    // ---- 编译 ----
    public QueryWrapper<?> compile(ObjectTypeDef typeDef, String dsl) {
        QueryWrapper<?> wrapper = new QueryWrapper<>();
        if (dsl == null || dsl.isBlank()) {
            return wrapper;
        }
        Node root = new Parser(dsl).parse();
        build(typeDef, root).accept(wrapper);
        return wrapper;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Consumer build(ObjectTypeDef typeDef, Node node) {
        if (node instanceof PredNode p) {
            return w -> applyPredicate(typeDef, (QueryWrapper) w, p);
        }
        if (node instanceof OrNode o) {
            List<Consumer> cs = o.children().stream()
                    .map(c -> build(typeDef, c)).toList();
            return w -> {
                for (int i = 0; i < cs.size(); i++) {
                    Consumer c = cs.get(i);
                    if (i == 0) {
                        ((QueryWrapper) w).and(c);
                    } else {
                        ((QueryWrapper) w).or(c);
                    }
                }
            };
        }
        if (node instanceof NotNode n) {
            Consumer inner = build(typeDef, n.child());
            return w -> ((QueryWrapper) w).not(inner);
        }
        // AndNode
        List<Consumer> cs = ((AndNode) node).children().stream()
                .map(c -> build(typeDef, c)).toList();
        return w -> cs.forEach(c -> ((QueryWrapper) w).and(c));
    }

    private void applyPredicate(ObjectTypeDef typeDef, QueryWrapper<?> w, PredNode p) {
        String path = p.path();
        if (path.equals("attributes") || path.startsWith("attributes.")) {
            applyAttributes(typeDef, w, p);
            return;
        }
        if (path.equals("payload") || path.startsWith("payload.")) {
            applyPayload(typeDef, w, p);
            return;
        }
        // 物理列白名单
        FieldDef f = typeDef.field(path);
        if (f == null || !f.queryable()) {
            throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
        String column = toSnake(path);
        if (f.type() == FieldType.JSON) {
            applyJsonArray(w, column, p.op(), p.value());
            return;
        }
        applyColumn(w, column, p.op(), p.value(), f.type());
    }

    /** jsonb 数组列（如 tags）：CONTAINS/== → 包含（@>）；EXISTS → 非空数组。字符串值包装为单元素数组。 */
    private void applyJsonArray(QueryWrapper<?> w, String column, String op, Object value) {
        switch (op) {
            case "CONTAINS", "==" -> {
                Object v = jsonArrayValue(value);
                w.apply(column + " @> {0}::jsonb", JsonUtils.write(v));
            }
            case "!=" -> {
                Object v = jsonArrayValue(value);
                w.apply("NOT (" + column + " @> {0}::jsonb)", JsonUtils.write(v));
            }
            case "EXISTS" -> w.ne(column, "[]");
            // LLM 常写 tags LIKE '%vip%'：jsonb 数组按文本 LIKE（cast 为 text，参数化防注入）
            case "LIKE" -> w.apply("cast(" + column + " as text) LIKE {0}", String.valueOf(value));
            // jsonb 数组 IN：任一成员包含即命中（OR 拼接 @>）
            case "IN" -> {
                List<?> vs = (List<?>) value;
                for (int i = 0; i < vs.size(); i++) {
                    String expr = column + " @> {0}::jsonb";
                    Object item = JsonUtils.write(List.of(vs.get(i)));
                    if (i == 0) {
                        w.apply(expr, item);
                    } else {
                        w.or().apply(expr, item);
                    }
                }
            }
            case "NOT IN" -> {
                List<?> vs = (List<?>) value;
                if (!vs.isEmpty()) {
                    StringBuilder expr = new StringBuilder("NOT (");
                    String[] ph = new String[vs.size()];
                    for (int i = 0; i < vs.size(); i++) {
                        if (i > 0) {
                            expr.append(" OR ");
                        }
                        expr.append(column).append(" @> {").append(i).append("}::jsonb");
                        ph[i] = JsonUtils.write(List.of(vs.get(i)));
                    }
                    expr.append(")");
                    w.apply(expr.toString(), (Object[]) ph);
                }
            }
            default -> throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
    }

    /** 值归一：已是 List 直接用；JSON 数组文本（如 '["vip","gold"]'，LLM 常见形态）解析成 List；其余包装单元素。 */
    private static Object jsonArrayValue(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        String s = String.valueOf(value).trim();
        if (s.startsWith("[")) {
            try {
                Object parsed = JsonUtils.read(s, new com.fasterxml.jackson.core.type.TypeReference<List<?>>() {
                });
                if (parsed instanceof List<?> list) {
                    return list;
                }
            } catch (Exception ignored) {
                // 非合法 JSON 数组文本：按单元素处理
            }
        }
        return List.of(String.valueOf(value));
    }

    private void applyColumn(QueryWrapper<?> w, String column, String op, Object value, FieldType type) {
        switch (op) {
            case "==" -> w.eq(column, value);
            case "!=" -> w.ne(column, value);
            case "<=" -> w.le(column, value);
            case ">=" -> w.ge(column, value);
            case "<" -> w.lt(column, value);
            case ">" -> w.gt(column, value);
            case "LIKE" -> w.like(column, String.valueOf(value));
            case "BETWEEN" -> {
                List<?> v = (List<?>) value;
                w.between(column, v.get(0), v.get(1));
            }
            case "IN" -> w.in(column, (List<?>) value);
            case "NOT IN" -> w.notIn(column, (List<?>) value);
            case "CONTAINS" -> w.like(column, String.valueOf(value));
            case "EXISTS" -> w.isNotNull(column);
            default -> throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
    }

    /** attributes.* 动态字段：jsonb containment —— attributes @> {"k": v}；EXISTS → ? 'k'。 */
    private void applyAttributes(ObjectTypeDef typeDef, QueryWrapper<?> w, PredNode p) {
        String path = p.path();
        boolean bare = path.length() == "attributes".length();
        String key = bare ? "" : path.substring("attributes.".length());
        String col = CustomerEntity.COL_ATTRIBUTES;
        String op = p.op();
        switch (op) {
            // 无键 EXISTS = 有任意属性；有键 EXISTS = 键存在（jsonb_exists 避开 apply 中 ? 占位符冲突）
            case "EXISTS" -> {
                if (bare) {
                    w.ne(col, "{}");
                } else {
                    w.apply("jsonb_exists(" + col + ", {0})", key);
                }
            }
            case "CONTAINS" -> w.apply(col + "->{0} @> {1}::jsonb", key, JsonUtils.write(p.value()));
            case "==" -> w.apply(col + " @> {0}::jsonb", JsonUtils.write(Map.of(key, p.value())));
            case "!=" -> w.apply("NOT (" + col + " @> {0}::jsonb)", JsonUtils.write(Map.of(key, p.value())));
            default -> throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
    }

    /** payload.* 动态字段：同 attributes 机制（事件载荷关键词查询）。 */
    private void applyPayload(ObjectTypeDef typeDef, QueryWrapper<?> w, PredNode p) {
        String path = p.path();
        boolean bare = path.length() == "payload".length();
        String key = bare ? "" : path.substring("payload.".length());
        String col = EventEntity.COL_PAYLOAD;
        switch (p.op()) {
            case "EXISTS" -> {
                if (bare) {
                    w.ne(col, "{}");
                } else {
                    w.apply("jsonb_exists(" + col + ", {0})", key);
                }
            }
            case "CONTAINS" -> w.apply(col + "->{0} @> {1}::jsonb", key, JsonUtils.write(p.value()));
            case "==", "!=" -> w.apply(col + "->{0} @> {1}::jsonb", key, JsonUtils.write(p.value()));
            default -> throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
    }

    // ---- 词法/语法 ----
    private record Token(String text, String kind) {
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        Node parse() {
            Node n = orExpr();
            skipWs();
            if (pos < src.length()) {
                throw syntax();
            }
            return n;
        }

        private Node orExpr() {
            List<Node> cs = new ArrayList<>();
            cs.add(andExpr());
            while (true) {
                int save = pos;
                skipWs();
                if (matchWord("OR") || matchWord("||")) {
                    cs.add(andExpr());
                } else {
                    pos = save;
                    break;
                }
            }
            return cs.size() == 1 ? cs.get(0) : new OrNode(cs);
        }

        private Node andExpr() {
            List<Node> cs = new ArrayList<>();
            cs.add(primary());
            while (true) {
                int save = pos;
                skipWs();
                if (matchWord("AND") || matchWord("&&")) {
                    cs.add(primary());
                } else {
                    pos = save;
                    break;
                }
            }
            return cs.size() == 1 ? cs.get(0) : new AndNode(cs);
        }

        private Node primary() {
            skipWs();
            // 一元 NOT 前缀（LLM 常写 `not (…)` / `NOT condition`）：递归否定一个 primary。
            // 与二元 NOT IN 不冲突——NOT 位于 primary 起点时其后必为 '(' 或 predicate（path），
            // 而 NOT IN 的 NOT 出现在已完成 path 的词法之后，由 op() 消费。
            if (matchWord("NOT")) {
                return new NotNode(primary());
            }
            if (pos < src.length() && src.charAt(pos) == '(') {
                pos++;
                Node inner = orExpr();
                skipWs();
                if (pos >= src.length() || src.charAt(pos) != ')') {
                    throw syntax();
                }
                pos++;
                return inner;
            }
            return predicate();
        }

        private PredNode predicate() {
            String path = path();
            skipWs();
            String op = op();
            skipWs();
            Object value = "EXISTS".equals(op) ? null : value();
            return new PredNode(path, op, value);
        }

        private String path() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw syntax();
            }
            return src.substring(start, pos);
        }

        private String op() {
            // 按长度降序匹配；大小写不敏感（LLM 常输出小写 contains/in/and）；'=' 归一为 '=='
            for (String candidate : new String[]{"NOT IN", "<=", ">=", "!=", "==", "=",
                    "LIKE", "BETWEEN", "CONTAINS", "EXISTS", "IN", "<", ">"}) {
                int save = pos;
                skipWs();
                if (src.regionMatches(true, pos, candidate, 0, candidate.length())) {
                    pos += candidate.length();
                    return "=".equals(candidate) ? "==" : candidate;
                }
                pos = save;
            }
            throw syntax();
        }

        private Object value() {
            char c = src.charAt(pos);
            // 单/双引号字符串等价（LLM 常输出 JSON 风格双引号）
            if (c == '\'' || c == '"') {
                char quote = c;
                pos++;
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && src.charAt(pos) != quote) {
                    sb.append(src.charAt(pos));
                    pos++;
                }
                if (pos >= src.length()) {
                    throw syntax();
                }
                pos++;
                return sb.toString();
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                pos++;
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    skipWs();
                    list.add(value());
                    skipWs();
                    if (pos < src.length() && src.charAt(pos) == ',') {
                        pos++;
                        continue;
                    }
                    if (pos < src.length() && src.charAt(pos) == ']') {
                        pos++;
                        break;
                    }
                    throw syntax();
                }
                return list;
            }
            int start = pos;
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.' || ch == ':') {
                    pos++;
                } else {
                    break;
                }
            }
            String raw = src.substring(start, pos);
            if (raw.isEmpty()) {
                throw syntax();
            }
            if (raw.equals("true") || raw.equals("false")) {
                return Boolean.parseBoolean(raw);
            }
            if (raw.startsWith("-") || Character.isDigit(raw.charAt(0))) {
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    return Double.parseDouble(raw);
                }
            }
            return raw;
        }

        private boolean matchWord(String word) {
            if (!src.regionMatches(true, pos, word, 0, word.length())) {
                return false;
            }
            int end = pos + word.length();
            if (end < src.length() && (Character.isLetterOrDigit(src.charAt(end)) || src.charAt(end) == '_')) {
                return false; // 词边界：ORANGE 不匹配 OR
            }
            pos = end;
            return true;
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private BizException syntax() {
            return new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
    }

    public static String toSnake(String camel) {
        return Texts.toSnake(camel);
    }

    /** snake_case → camelCase（投影键还原：实体 Jackson 序列化键为 camel）。 */
    public static String toCamel(String snake) {
        return Texts.toCamel(snake);
    }
}