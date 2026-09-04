package com.eaagent.ontology.action;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Action 注册表（3.4）：按名称路由 applyAction；未注册抛 E-13001。
 */
@Component
public class ActionRegistry {

    private final Map<String, Action> actions = new ConcurrentHashMap<>();

    public ActionRegistry(java.util.List<Action> discovered) {
        for (Action a : discovered) {
            actions.put(a.meta().name(), a);
        }
    }

    public Action get(String name) {
        Action action = actions.get(name);
        if (action == null) {
            throw new BizException(ErrorCode.ACTION_NOT_REGISTERED);
        }
        return action;
    }

    public Map<String, Action> all() {
        return actions;
    }
}