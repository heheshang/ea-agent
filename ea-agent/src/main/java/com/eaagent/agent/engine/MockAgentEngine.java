package com.eaagent.agent.engine;

import com.eaagent.agent.event.EngineEvent;
import com.eaagent.common.JsonUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Mock 引擎（4.1 降级路径）：无外部模型依赖，演示完整状态机与 SSE 协议。
 * 条件：ea.agentscope.model 未配置时生效。
 */
@Component
@ConditionalOnProperty(name = "ea.agentscope.model", havingValue = "", matchIfMissing = true)
public class MockAgentEngine implements AgentEngine {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Flux<EngineEvent> stream(RunContext rc, String userInput) {
        List<EngineEvent> steps = List.of(
                new EngineEvent("plan", JsonUtils.write(Map.of(
                        "goal", userInput,
                        "steps", List.of(
                                Map.of("name", "解析目标与权限", "desc", "识别目标人群与操作权限"),
                                Map.of("name", "查询客户画像", "desc", "按活跃状态拉取候选人"),
                                Map.of("name", "评估触达窗口", "desc", "核对退订与频控"),
                                Map.of("name", "输出建议", "desc", "汇总触达方案"))))),
                new EngineEvent("tool_call", JsonUtils.write(Map.of(
                        "tool", "queryCustomers",
                        "args", Map.of("filter", "status == 'ACTIVE'", "limit", 10)))),
                new EngineEvent("action_result", JsonUtils.write(Map.of(
                        "tool", "queryCustomers",
                        "rows", List.of(
                                Map.of("id", 1, "name", "张伟", "status", "ACTIVE", "phone", "138****0001"),
                                Map.of("id", 2, "name", "李娜", "status", "ACTIVE", "email", "li***@example.com"))))),
                new EngineEvent("text_delta", JsonUtils.write(Map.of(
                        "text", "已定位 2 位活跃客户。张伟近 30 天有 3 次下单，偏好晚八点触达；李娜偏好邮件渠道，近期无退订记录。"))),
                new EngineEvent("text_delta", JsonUtils.write(Map.of(
                        "text", "建议对张伟通过 console 通道推送复购提醒，李娜可推送本周活动摘要。"))));
        return Flux.fromIterable(steps).delayElements(Duration.ofMillis(200));
    }
}