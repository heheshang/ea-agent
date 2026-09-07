package com.eaagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** agent 创建活动必须携带触发规则：createCampaign 缺省/空白 trigger_rule 拒绝，其余动作放行。 */
class AgentToolRegistryTest {

    @Test
    void approvalModeFailureCannotExecuteWriteAction() {
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var actions = org.mockito.Mockito.mock(com.eaagent.ontology.action.ActionRegistry.class);
        org.mockito.Mockito.when(actions.all()).thenReturn(Map.of());
        var registry = new AgentToolRegistry(null, null, null, null, null, actions,
                null, null, null, redis);
        org.mockito.Mockito.when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
        var tool = registry.new ApplyAction(1L, 2L, "OPERATOR", "chat-3");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> tool.execute(Map.of("action", "pauseCampaign", "args", Map.of("campaign_id", 7L))));
        org.mockito.Mockito.verify(actions, org.mockito.Mockito.never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    private static Map<String, Object> rule(Object triggerRule) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "x");
        args.put("audience_id", 1L);
        args.put("channel", "sms");
        args.put("template_id", 1L);
        if (triggerRule != null) {
            args.put("trigger_rule", triggerRule);
        }
        return args;
    }

    @Test
    void nonCreateActionPasses() {
        assertNull(AgentToolRegistry.validateCreateCampaignRule("pauseCampaign", Map.of("campaign_id", 1L)));
    }

    @Test
    void missingTriggerRuleRejected() {
        assertNotNull(AgentToolRegistry.validateCreateCampaignRule("createCampaign", rule(null)));
    }

    @Test
    void emptyTriggerRuleRejected() {
        assertNotNull(AgentToolRegistry.validateCreateCampaignRule("createCampaign", rule(Map.of())));
    }

    @Test
    void ruleWithoutEventTypeRejected() {
        assertNotNull(AgentToolRegistry.validateCreateCampaignRule(
                "createCampaign", rule(Map.of("window", "1d"))));
    }

    @Test
    void ruleStringWithoutEventTypeRejected() {
        assertNotNull(AgentToolRegistry.validateCreateCampaignRule(
                "createCampaign", rule("{}")));
    }

    @Test
    void ruleWithEventTypeAccepted() {
        assertNull(AgentToolRegistry.validateCreateCampaignRule(
                "createCampaign", rule(Map.of("event_type", "order_placed", "window", "1d"))));
    }

    @Test
    void ruleJsonStringWithEventTypeAccepted() {
        assertNull(AgentToolRegistry.validateCreateCampaignRule(
                "createCampaign", rule("{\"event_type\":\"coupon_used\",\"window\":\"2d\"}")));
    }
}