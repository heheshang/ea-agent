package com.eaagent.ontology.service;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.CustomerEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 触达人群快照：build 固化成员 + memberIds 只认快照（空快照=空发送，不重算）；
 * 存量活动无快照 → 惰性现算并回填 campaign（自愈路径）。
 */
class AudienceSnapshotServiceTest {

    private static final long TENANT = 1L;
    private static final long AUDIENCE = 9L;

    private final AudienceMapper audienceMapper = mock(AudienceMapper.class);
    private final CampaignMapper campaignMapper = mock(CampaignMapper.class);
    // AudienceResolver 为 @Service 具体类（Java 26 下 Mockito 无法内联增强具体类），用匿名子类覆写 resolve 桩
    private final AtomicInteger resolveCalls = new AtomicInteger();
    private java.util.function.BiFunction<Long, AudienceEntity, List<CustomerEntity>> resolveFn =
            (tenant, a) -> List.of();
    private final AudienceResolver audienceResolver = new AudienceResolver(null, null, null) {
        @Override
        public List<CustomerEntity> resolve(Long tenantId, AudienceEntity audience) {
            resolveCalls.incrementAndGet();
            return resolveFn.apply(tenantId, audience);
        }
    };
    private final AudienceSnapshotService svc =
            new AudienceSnapshotService(audienceMapper, audienceResolver, campaignMapper);

    private static AudienceEntity audience() {
        AudienceEntity a = new AudienceEntity();
        a.setId(AUDIENCE);
        a.setTenantId(TENANT);
        a.setName("跑步人群");
        a.setMode("DYNAMIC");
        a.setRule("attributes.hobby == '跑步'");
        return a;
    }

    private static List<CustomerEntity> customers(Long... ids) {
        return java.util.Arrays.stream(ids).map(id -> {
            CustomerEntity c = new CustomerEntity();
            c.setId(id);
            c.setTenantId(TENANT);
            return c;
        }).toList();
    }

    @Test
    void buildFreezesMemberListWithCountAndTimestamp() {
        resolveFn = (tenant, a) -> customers(101L, 102L, 103L);
        when(audienceMapper.selectOne(any())).thenReturn(audience());

        Map<String, Object> snap = svc.build(TENANT, AUDIENCE);

        assertEquals(AUDIENCE, snap.get("audience_id"));
        assertEquals("跑步人群", snap.get("audience_name"));
        assertEquals("DYNAMIC", snap.get("mode"));
        assertEquals("attributes.hobby == '跑步'", snap.get("rule"));
        assertEquals(3, snap.get("member_count"));
        assertEquals(List.of(101L, 102L, 103L), snap.get("customer_ids"));
        assertNotNull(snap.get("snapshot_at"));
        assertEquals(1, resolveCalls.get());
        verify(campaignMapper, never()).updateById(any(CampaignEntity.class));
    }

    @Test
    void buildRejectsMissingAudience() {
        when(audienceMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> svc.build(TENANT, AUDIENCE));
        assertEquals(ErrorCode.OBJECT_NOT_FOUND, ex.getErrorCode());
        assertEquals(0, resolveCalls.get());
        verify(campaignMapper, never()).updateById(any(CampaignEntity.class));
    }

    @Test
    void memberIdsUsesSnapshotWithoutRecompute() {
        CampaignEntity c = new CampaignEntity();
        c.setAudienceId(AUDIENCE);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("member_count", 2);
        snap.put("customer_ids", List.of(111L, 222L));
        c.setAudienceSnapshot(snap);

        List<Long> ids = svc.memberIds(TENANT, c);

        assertEquals(List.of(111L, 222L), ids);
        assertEquals(0, resolveCalls.get()); // 有快照绝不实时重算
        verify(campaignMapper, never()).updateById(any(CampaignEntity.class));
    }

    @Test
    void emptySnapshotSendsNothingWithoutRecompute() {
        CampaignEntity c = new CampaignEntity();
        c.setAudienceId(AUDIENCE);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("member_count", 0);
        snap.put("customer_ids", List.of()); // 合法空人群：键存在即快照，不得按规则重算
        c.setAudienceSnapshot(snap);

        assertEquals(List.of(), svc.memberIds(TENANT, c));
        assertEquals(0, resolveCalls.get());
        verify(campaignMapper, never()).updateById(any(CampaignEntity.class));
    }

    @Test
    void legacyCampaignWithoutSnapshotBackfills() {
        CampaignEntity c = new CampaignEntity();
        c.setId(5L);
        c.setAudienceId(AUDIENCE);
        c.setAudienceSnapshot(null);
        c.setUpdatedAt(null);
        resolveFn = (tenant, a) -> customers(7L, 8L);
        when(audienceMapper.selectOne(any())).thenReturn(audience());

        List<Long> ids = svc.memberIds(TENANT, c);

        assertEquals(List.of(7L, 8L), ids);
        assertNotNull(c.getAudienceSnapshot());
        assertEquals(2, c.getAudienceSnapshot().get("member_count"));
        assertNotNull(c.getUpdatedAt());
        assertEquals(1, resolveCalls.get());
        verify(campaignMapper).updateById(c);
    }
}