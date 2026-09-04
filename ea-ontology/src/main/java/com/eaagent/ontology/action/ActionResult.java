package com.eaagent.ontology.action;

import java.util.Map;

/**
 * Action 执行结果：幂等重放下 data 携带首次执行结果（9.4 审计可回放）。
 */
public record ActionResult(boolean success, String requestId, String action, Map<String, Object> data) {

    public static ActionResult ok(String requestId, String action, Map<String, Object> data) {
        return new ActionResult(true, requestId, action, data);
    }
}