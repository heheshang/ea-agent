package com.eaagent.ontology.function;

import java.util.Map;

/**
 * Function 契约（4.2 Call Function / 架构 5.2）：决策咨询函数 —— 预测模型、优化算法、统计画像等
 * 只读后台逻辑，由 FunctionRegistry 按 name 路由（与 ActionRegistry 对称）。
 * 执行必须按 tenantId 过滤数据并做对象归属校验（6.3）；函数无副作用、非变更操作，
 * 强制约束（频控 / 退订 / 时段）一律在 Action 管线执行（H），不依赖 LLM 自觉调用。
 */
public interface Function {

    /** 函数名（callFunction 的 name 参数，全局唯一）。 */
    String name();

    /** 自然语言描述（进入 callFunction 工具描述，供 LLM 选择）。 */
    String description();

    /** 参数 JSON Schema properties（不含 type/required 外壳，供文档与校验使用）。 */
    Map<String, Object> params();

    /** 执行：只读咨询，需自行校验参数并执行租户归属校验。 */
    Map<String, Object> execute(long tenantId, Map<String, Object> args);
}