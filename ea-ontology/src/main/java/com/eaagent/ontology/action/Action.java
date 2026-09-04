package com.eaagent.ontology.action;

/**
 * Action 契约（3.4）：meta() 声明 + execute() 执行。
 * 管线步骤由 AbstractAction 统一承载；实现类只写业务 doExecute。
 */
public interface Action {

    ActionMeta meta();

    ActionResult execute(ActionContext ctx, ActionRequest req);
}