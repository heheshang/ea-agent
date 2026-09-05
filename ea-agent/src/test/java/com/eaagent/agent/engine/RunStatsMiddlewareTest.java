package com.eaagent.agent.engine;

import com.eaagent.ontology.mapper.AgentToolCallMapper;
import com.eaagent.ontology.model.AgentToolCallEntity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RunStatsMiddleware.toEntity：调用链明细行转换（运行时实时落库与引擎完成时兜底共用）。 */
class RunStatsMiddlewareTest {

    private static Map<String, Object> tc(Object seq, String name, Object target, Object params,
                                          Object dms, boolean ok, Object error) {
        Map<String, Object> m = new HashMap<>();
        m.put("seq", seq);
        m.put("name", name);
        m.put("target", target);
        m.put("params", params);
        m.put("duration_ms", dms);
        m.put("ok", ok);
        m.put("error", error);
        return m;
    }

    @Test
    void applyActionWithTargetMapsToActionKind() {
        AgentToolCallEntity e = RunStatsMiddleware.toEntity(7, 42,
                tc(3, "applyAction", "updateCustomer", "{\"action\":\"updateCustomer\"}", 150, true, null));
        assertEquals(7L, e.getTenantId());
        assertEquals(42L, e.getRunId());
        assertEquals(3, e.getSeq());
        assertEquals("action", e.getKind());
        assertEquals("applyAction", e.getName());
        assertEquals("updateCustomer", e.getTarget());
        assertEquals("{\"action\":\"updateCustomer\"}", e.getArgs());
        assertEquals(150, e.getDurationMs());
        assertTrue(e.getOk());
        assertNull(e.getError());
    }

    @Test
    void callFunctionWithTargetMapsToFunctionKind() {
        AgentToolCallEntity e = RunStatsMiddleware.toEntity(1, 9,
                tc(1, "callFunction", "summarize", "{\"name\":\"summarize\"}", 800, false, "TOOL_ERROR"));
        assertEquals("function", e.getKind());
        assertEquals("summarize", e.getTarget());
        assertEquals(800, e.getDurationMs());
        assertEquals(false, e.getOk());
        assertEquals("TOOL_ERROR", e.getError());
    }

    @Test
    void queryToolWithoutTargetMapsToToolKind() {
        AgentToolCallEntity e = RunStatsMiddleware.toEntity(1, 9,
                tc(2, "queryCustomers", null, "{\"tag\":\"test\"}", null, true, null));
        assertEquals("tool", e.getKind());
        assertNull(e.getTarget());
        assertEquals("{\"tag\":\"test\"}", e.getArgs());
        assertNull(e.getDurationMs()); // 无计时数据 → null（运行中 Start 事件缺失场景）
    }

    @Test
    void seqNullAndNonNumericDurationAreTolerated() {
        AgentToolCallEntity e = RunStatsMiddleware.toEntity(1, 9,
                tc(null, "some_tool", null, "params", "n/a", true, null));
        assertNull(e.getSeq());
        assertNull(e.getDurationMs());
    }

    @Test
    void recordKbBecomesFirstStepAndClearsAfterDrain() {
        RunStatsMiddleware mw = new RunStatsMiddleware("m", "s", null);
        mw.begin(7, 42);
        mw.recordKb("查退货款规则", 3, 15);
        List<Map<String, Object>> out = mw.drainToolCalls();
        assertEquals(1, out.size());
        Map<String, Object> kb = out.get(0);
        assertEquals(1, kb.get("seq"));
        assertEquals("knowledge_search", kb.get("name"));
        assertEquals(true, kb.get("ok"));
        assertNull(kb.get("error"));
        assertEquals(15L, kb.get("duration_ms"));
        // 取走即清空，本轮 kb 步骤不残留到下一轮
        assertTrue(mw.drainToolCalls().isEmpty());
    }

    @Test
    void recordKbNoHitKeepsStepWithOkFalse() {
        RunStatsMiddleware mw = new RunStatsMiddleware("m", "s", null);
        mw.begin(7, 42);
        mw.recordKb("无匹配的疑问", 0, 3);
        Map<String, Object> kb = mw.drainToolCalls().get(0);
        assertEquals(false, kb.get("ok"));
        assertEquals("no_hit", kb.get("error"));
    }

    @Test
    void kbRowMapsToKbKindEntity() {
        AgentToolCallEntity e = RunStatsMiddleware.toEntity(7, 42,
                tc(1, "knowledge_search", null, "goal", 12, true, null));
        assertEquals("kb", e.getKind());
        assertEquals(1, e.getSeq());
        assertEquals("knowledge_search", e.getName());
    }
}