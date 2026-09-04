package com.eaagent.agent.engine;

import com.eaagent.agent.event.EngineEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 执行引擎抽象（详细设计 4.1）：Mock 与 agentscope 两种实现，按配置切换。
 */
public interface AgentEngine {

    /** 引擎是否可用（agentscope 未配置/构建失败 → false，回退 Mock）。 */
    boolean available();

    /**
     * 流式执行一轮对话。
     *
     * @param rc        运行上下文
     * @param userInput 用户目标文本
     * @return EngineEvent 流，以 done 收尾
     */
    Flux<EngineEvent> stream(RunContext rc, String userInput);
}