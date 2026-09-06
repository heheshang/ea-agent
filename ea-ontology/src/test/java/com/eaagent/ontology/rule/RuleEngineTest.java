package com.eaagent.ontology.rule;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.ontology.type.TypeRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSL 宽容化回归：不抛异常 + 生成的 SQL 含预期操作符 + 参数值正确绑定 + 越界列仍拒绝。
 * 用例来自生产 run tool_calls 中的 LLM 真实输出形态。
 *
 * 注意：MyBatis-Plus 3.5.x paramNameValuePairs 在 getCustomSqlSegment() 后才填充，
 * 故两者同取一个 wrapper。
 */
class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    /** 编译一次并返回 wrapper（惰性参数已填充）。 */
    private QueryWrapper<?> compile(String dsl) {
        QueryWrapper<?> w = engine.compile(TypeRegistry.get("customer"), dsl);
        w.getCustomSqlSegment(); // 触发参数填充
        return w;
    }

    private String sql(String dsl) {
        return compile(dsl).getCustomSqlSegment();
    }

    private Map<String, Object> params(String dsl) {
        return compile(dsl).getParamNameValuePairs();
    }

    @Test
    void lessThanOrEqual() {
        assertTrue(sql("id <= 11").contains("<= "));
        assertTrue(params("id <= 11").containsValue(11L));
    }

    @Test
    void comparisons() {
        assertTrue(sql("id >= 100").contains(">= "));
        assertTrue(sql("id < 5").contains("< "));
        assertTrue(sql("id > 5").contains("> "));
    }

    @Test
    void singleEqualNormalized() {
        assertTrue(sql("status = 'ACTIVE'").contains("= "));
        assertTrue(params("status = 'ACTIVE'").containsValue("ACTIVE"));
    }

    @Test
    void lowercaseContains() {
        assertTrue(sql("tags contains '\"vip\"'").contains("@>"));
        // 值 '“vip”' 是带引号的字符串 "vip"，包装成单元素数组 ["“vip”"]
        assertTrue(params("tags contains '\"vip\"'").containsValue("[\"\\\"vip\\\"\"]"));
    }

    @Test
    void lowercaseAndWithIn() {
        String s = sql("status == 'ACTIVE' and tags in ['VIP', 'GOLD']");
        // jsonb 数组 IN：两个 @> 成员包含 + OR 连接（值参数化）
        assertTrue(s.contains("@>") && s.contains("OR"));
        assertTrue(params("status == 'ACTIVE' and tags in ['VIP', 'GOLD']").containsValue("[\"VIP\"]"));
    }

    @Test
    void likeWildcard() {
        assertTrue(sql("tags LIKE '%vip%'").contains("LIKE"));
        assertTrue(params("tags LIKE '%vip%'").containsValue("%vip%"));
    }

    @Test
    void jsonArrayTextValue() {
        // LLM 常把 JSON 数组文本包在单引号里：tags == '["vip"]' 应解析为数组包含而非字面量
        assertTrue(sql("tags == '[\"vip\"]'").contains("@>"));
        assertTrue(params("tags == '[\"vip\"]'").containsValue("[\"vip\"]"));
    }

    @Test
    void doubleQuotedString() {
        assertTrue(sql("external_id == \"abc123\"").contains("= "));
        assertTrue(params("external_id == \"abc123\"").containsValue("abc123"));
    }

    @Test
    void parenthesesAndNested() {
        String s = sql("(status == 'ACTIVE' OR status == 'INACTIVE') AND id > 3");
        assertTrue(s.contains("OR") && s.contains("AND") && s.contains("> "));
        var p = params("(status == 'ACTIVE' OR status == 'INACTIVE') AND id > 3");
        assertTrue(p.containsValue("ACTIVE") && p.containsValue("INACTIVE") && p.containsValue(3L));
    }

    @Test
    void unknownColumnRejected() {
        assertThrows(BizException.class, () -> sql("password == 'x'"));
    }

    /** 一元 NOT：LLM 常写 `and not (…)`（如排除沉睡客户），此前解析失败抛 E-12003。 */
    @Test
    void unaryNotNegatesGroup() {
        String dsl = "status == 'ACTIVE' && not (tags CONTAINS '沉睡')";
        String s = sql(dsl);
        assertTrue(s.contains("AND (NOT ("));
        assertTrue(s.contains("status"));
        assertTrue(s.contains("tags"));
        // jsonb @> containment 把值序列化为数组文本 ["沉睡"]（与正向 CONTAINS 同格式）
        assertTrue(params(dsl).containsValue("[\"沉睡\"]"));
    }

    @Test
    void unaryNotWithoutParens() {
        assertTrue(sql("not tags CONTAINS 'VIP'").contains("NOT ("));
    }

    @Test
    void unaryNotNegatesScalar() {
        assertTrue(sql("not (status == 'SLEEP')").contains("NOT ("));
    }
}