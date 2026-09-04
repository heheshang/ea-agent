package com.eaagent.ontology.rule;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.EventEntity;
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
 * orExpr   := andExpr (('OR'|'||') andExpr)*
 * andExpr  := primary (('AND'|'&&') primary)*
 * primary  := '(' expr ')' | predicate
 * predicate:= path op value
 * path     := ident ('.' ident)*
 * op       := '==' | '!=' | 'IN' | 'NOT IN' | 'CONTAINS' | 'BETWEEN' | 'EXISTS'
 * </pre>
 * 编译产物为参数化 QueryWrapper（值全部 ? 绑定；列名来自白名单常量，杜绝注入）。
 * 白名单越界抛 E-12003；类型未知抛 E-12004。
 */
public final class RuleEngine {

    // ---- AST ----
    private sealed interface Node permits AndNode, OrNode, PredNode {
    }
    private record AndNode(List<Node> children) implements Node {
    }
    private record OrNode(List<Node> children) implements Node {
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
        String column = toSnake(path);
        if (!typeDef.isQueryable(path)) {
            throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
        applyColumn(w, column, p.op(), p.value(), typeDef.field(path).type());
    }

    private void applyColumn(QueryWrapper<?> w, String column, String op, Object value, FieldType type) {
        switch (op) {
            case "==" -> w.eq(column, value);
            case "!=" -> w.ne(column, value);
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
        String attrPath = p.path().substring("attributes.".length());
        String key = attrPath;
        String col = CustomerEntity.COL_ATTRIBUTES;
        String op = p.op();
        switch (op) {
            case "EXISTS" -> w.apply(col + " ? {0}", key);
            case "CONTAINS" -> w.apply(col + "->{0} @> {1}::jsonb", key, JsonUtils.write(p.value()));
            case "==" -> w.apply(col + " @> {0}::jsonb", JsonUtils.write(Map.of(key, p.value())));
            case "!=" -> w.apply("NOT (" + col + " @> {0}::jsonb)", JsonUtils.write(Map.of(key, p.value())));
            default -> throw new BizException(ErrorCode.DSL_PARSE_ERROR);
        }
    }

    /** payload.* 动态字段：同 attributes 机制（事件载荷关键词查询）。 */
    private void applyPayload(ObjectTypeDef typeDef, QueryWrapper<?> w, PredNode p) {
        String key = p.path().substring("payload.".length());
        String col = EventEntity.COL_PAYLOAD;
        switch (p.op()) {
            case "EXISTS" -> w.apply(col + " ? {0}", key);
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
            Object value = value();
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
            for (String candidate : new String[]{"NOT IN", "==", "!=", "BETWEEN", "CONTAINS", "EXISTS", "IN"}) {
                int save = pos;
                skipWs();
                if (src.startsWith(candidate, pos)) {
                    pos += candidate.length();
                    return candidate;
                }
                pos = save;
            }
            throw syntax();
        }

        private Object value() {
            char c = src.charAt(pos);
            if (c == '\'') {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && src.charAt(pos) != '\'') {
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
            if (!src.startsWith(word, pos)) {
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
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** snake_case → camelCase（投影键还原：实体 Jackson 序列化键为 camel）。 */
    public static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }
}