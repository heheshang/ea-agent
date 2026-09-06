package com.eaagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** agent 创建活动必须携带触发规则：createCampaign 缺省/空白 trigger_rule 拒绝，其余动作放行。 */
class AgentToolRegistryTest {

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