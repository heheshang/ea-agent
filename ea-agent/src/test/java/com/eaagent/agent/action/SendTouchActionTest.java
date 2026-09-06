package com.eaagent.agent.action;

import com.eaagent.channel.ChannelAdapter;
import com.eaagent.channel.ChannelAdapterRegistry;
import com.eaagent.ontology.mapper.ChannelConfigMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.mapper.UnsubscribeMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.ChannelConfigEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.TemplateEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * sendOneCustomer 频控闸（E-13004）：max_per_day 边界、滚动 TTL、未配置/非法值不限频不计数。
 * JDK 26 下 Byte Buddy 无法内联具体类，故 StringRedisTemplate / ChannelAdapterRegistry 用真实实例
 * （FakeRedis 覆写两个入口点；registry 包真实 adapter mock），其余未触达协作者传 null（仅 DAG 路径，
 * 不触碰 snapshot/routing/idempotency/actionLog）。
 */
class SendTouchActionTest {

    /** 只覆写频控用到的两个 Redis 入口，避免对具体类做 Mockito 内联。 */
    private static class FakeRedis extends StringRedisTemplate {
        final ValueOperations<String, String> valueOps;
        final List<String> expireKeys = new java.util.ArrayList<>();
        final List<Duration> expireTtls = new java.util.ArrayList<>();

        FakeRedis(ValueOperations<String, String> valueOps) {
            this.valueOps = valueOps;
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOps;
        }

        @Override
        public Boolean expire(String key, Duration timeout) {
            expireKeys.add(key);
            expireTtls.add(timeout);
            return true;
        }
    }

    private static DeliveryMapper deliveryMapper;

    private static SendTouchAction action(ChannelConfigMapper channelConfigMapper, FakeRedis redis) {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapter.channelType()).thenReturn("sms");
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of(adapter));
        UnsubscribeMapper unsubscribeMapper = mock(UnsubscribeMapper.class);
        when(unsubscribeMapper.selectOne(any())).thenReturn(null); // 未退订
        deliveryMapper = mock(DeliveryMapper.class);
        return new SendTouchAction(
                null, null, redis,         // actionLogMapper / idempotencyService 未触达
                null, null, null,          // campaign/template/customer mapper 未触达
                deliveryMapper, unsubscribeMapper, channelConfigMapper,
                null,                      // AudienceSnapshotService 未触达
                registry, null);           // TemplateRoutingService 未触达（DAG 直定模板命中缓存）
    }

    private static CustomerEntity customer() {
        CustomerEntity c = new CustomerEntity();
        c.setId(101L);
        c.setPhone("13800138000");
        return c;
    }

    private static CampaignEntity campaign() {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(1L);
        return campaign;
    }

    private static TemplateEntity template() {
        TemplateEntity t = new TemplateEntity();
        t.setId(9L);
        t.setContent("hi");
        t.setVars(java.util.List.of());
        t.setReviewStatus(TemplateEntity.REVIEW_APPROVED);
        return t;
    }

    private static ChannelConfigEntity config(int maxPerDay) {
        ChannelConfigEntity cfg = new ChannelConfigEntity();
        cfg.setFrequencyLimit(Map.of("max_per_day", maxPerDay));
        return cfg;
    }

    private static SendTouchAction.SendTarget target(String actionRequestId) {
        return new SendTouchAction.SendTarget(customer(), "sms", 9L, "n1", null, Map.of());
    }

    @SuppressWarnings("unchecked")
    @Test
    void overLimitSkipsWithoutDeliveryRow() {
        ChannelConfigMapper cfgMapper = mock(ChannelConfigMapper.class);
        when(cfgMapper.selectOne(any())).thenReturn(config(2)); // max_per_day=2
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        // 同一客户第 3 次触达计数 3 > 2 → 超限；前两次正常
        when(valueOps.increment(anyString())).thenReturn(1L, 2L, 3L);
        FakeRedis redis = new FakeRedis(valueOps);
        SendTouchAction action = action(cfgMapper, redis);

        String keyPrefix = "ea:fc:1:sms:101:";
        Map<String, Long> fcCache = new HashMap<>();
        SendTouchAction.SendOutcome first = action.sendOneCustomer(1L, campaign(),
                target("r1"), Map.of(9L, template()), fcCache, "r1");
        SendTouchAction.SendOutcome second = action.sendOneCustomer(1L, campaign(),
                target("r2"), Map.of(9L, template()), fcCache, "r2");
        SendTouchAction.SendOutcome third = action.sendOneCustomer(1L, campaign(),
                target("r3"), Map.of(9L, template()), fcCache, "r3");

        // 前两次：未超限 → 正常落库
        assertNotNull(first.delivery());
        assertNull(first.skip());
        assertNotNull(second.delivery());
        assertNull(second.skip());
        // 第三次：超限 → 跳过、不落库
        assertNull(third.delivery());
        assertEquals(SendTouchAction.SendOutcome.Skip.FREQUENCY_LIMITED, third.skip());
        // 计数键：ea:fc:{tenant}:{channel}:{customerId}:{date}，共 3 次 INCR
        verify(valueOps, times(3)).increment(argThat(k -> k.startsWith(keyPrefix)));
        // 仅首次计数设置滚动 TTL（当日剩余，>0 且 <24h），键一致
        assertEquals(1, redis.expireKeys.size());
        assertTrue(redis.expireKeys.get(0).startsWith(keyPrefix));
        Duration ttl = redis.expireTtls.get(0);
        assertTrue(ttl.compareTo(Duration.ZERO) > 0);
        assertTrue(ttl.compareTo(Duration.ofHours(24)) < 0);
        // 超限不落库：全流程仅前两次 insert
        verify(deliveryMapper, times(2)).insert(any(DeliveryEntity.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void unconfiguredLimitIsUnlimitedAndSkipsRedis() {
        ChannelConfigMapper cfgMapper = mock(ChannelConfigMapper.class);
        when(cfgMapper.selectOne(any())).thenReturn(null); // 无频道配置
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        FakeRedis redis = new FakeRedis(valueOps);
        SendTouchAction action = action(cfgMapper, redis);

        SendTouchAction.SendOutcome oc = action.sendOneCustomer(1L, campaign(),
                target("r1"), Map.of(9L, template()), new HashMap<>(), "r1");

        assertNotNull(oc.delivery());
        assertNull(oc.skip());
        verify(valueOps, never()).increment(anyString()); // 未配置 → 不计数不进 Redis
        verify(deliveryMapper, times(1)).insert(any(DeliveryEntity.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void invalidMaxPerDayFallsBackToUnlimited() {
        ChannelConfigMapper cfgMapper = mock(ChannelConfigMapper.class);
        ChannelConfigEntity cfg = new ChannelConfigEntity();
        cfg.setFrequencyLimit(Map.of("max_per_day", "3x")); // 非数字 → 按不限频处理
        when(cfgMapper.selectOne(any())).thenReturn(cfg);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        FakeRedis redis = new FakeRedis(valueOps);
        SendTouchAction action = action(cfgMapper, redis);

        SendTouchAction.SendOutcome oc = action.sendOneCustomer(1L, campaign(),
                target("r1"), Map.of(9L, template()), new HashMap<>(), "r1");

        assertNotNull(oc.delivery());
        assertNull(oc.skip());
        verify(valueOps, never()).increment(anyString());
    }
}