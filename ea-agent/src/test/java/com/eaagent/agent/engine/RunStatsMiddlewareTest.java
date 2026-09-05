package com.eaagent.agent.engine;

import com.eaagent.ontology.mapper.AgentToolCallMapper;
import com.eaagent.ontology.model.AgentToolCallEntity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
}