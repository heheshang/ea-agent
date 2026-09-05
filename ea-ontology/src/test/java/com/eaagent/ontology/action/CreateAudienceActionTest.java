package com.eaagent.ontology.action;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.AudienceMemberMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.AudienceMemberEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.service.AudienceResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createAudience：圈定人群路径（规则人群校验+落库 / 静态成员落库）；
 * 创建即校验——rule 缺失或非法在落库前抛错，不产生脏人群。
 */
class CreateAudienceActionTest {

    private final AudienceMapper audienceMapper = mock(AudienceMapper.class);
    private final AudienceMemberMapper memberMapper = mock(AudienceMemberMapper.class);
    // AudienceResolver 为 @Service 具体类（Java 26 下 Mockito 无法内联增强具体类），用匿名子类覆写 resolve 桩
    private final java.util.concurrent.atomic.AtomicInteger resolveCalls = new java.util.concurrent.atomic.AtomicInteger();
    private java.util.function.BiFunction<Long, AudienceEntity, List<CustomerEntity>> resolveFn =
            (tenant, a) -> List.of();
    private final AudienceResolver resolver = new AudienceResolver(null, null, null) {
        @Override
        public List<CustomerEntity> resolve(Long tenantId, AudienceEntity audience) {
            resolveCalls.incrementAndGet();
            return resolveFn.apply(tenantId, audience);
        }
    };
    private final CreateAudienceAction action = new CreateAudienceAction(
            mock(ActionLogMapper.class), new IdempotencyService(new StringRedisTemplate()), new StringRedisTemplate(),
            audienceMapper, memberMapper, resolver);

    private static ActionContext ctx() {
        return ActionContext.of(1L, 2L, "OPERATOR", null);
    }

    private static void assignIdOnInsert(AudienceMapper mapper, long id) {
        when(mapper.insert(any(AudienceEntity.class))).thenAnswer(inv -> {
            AudienceEntity a = inv.getArgument(0);
            a.setId(id);
            return 1;
        });
    }

    @Test
    void rejectsWhenNeitherRuleNorMembersGiven() {
        BizException ex = assertThrows(BizException.class,
                () -> action.execute(ctx(), ActionRequest.of(Map.of("name", "跑步人群"))));
        assertEquals(ErrorCode.ACTION_VALIDATION_FAILED, ex.getErrorCode());
        verify(audienceMapper, org.mockito.Mockito.never()).insert(any(AudienceEntity.class));
    }

    @Test
    void rejectsStaticEmptyMemberList() {
        BizException ex = assertThrows(BizException.class,
                () -> action.execute(ctx(), ActionRequest.of(
                        Map.of("name", "空人群", "member_customer_ids", List.of()))));
        assertEquals(ErrorCode.ACTION_VALIDATION_FAILED, ex.getErrorCode());
        verify(audienceMapper, org.mockito.Mockito.never()).insert(any(AudienceEntity.class));
    }

    @Test
    void dynamicAudiencePreviewsThenInserts() {
        assignIdOnInsert(audienceMapper, 42L);
        resolveFn = (tenant, a) -> List.of(customer(1L), customer(2L), customer(3L));

        Map<String, Object> out = action.execute(ctx(), ActionRequest.of(Map.of(
                "name", "跑步人群", "rule", "attributes.hobby == '跑步'"))).data();

        assertEquals(42L, out.get("audience_id"));
        assertEquals("跑步人群", out.get("name"));
        assertEquals("DYNAMIC", out.get("mode"));
        assertEquals(3, out.get("member_count"));
        ArgumentCaptor<AudienceEntity> captor = ArgumentCaptor.forClass(AudienceEntity.class);
        verify(audienceMapper).insert(captor.capture());
        assertEquals("attributes.hobby == '跑步'", captor.getValue().getRule());
        assertEquals("DYNAMIC", captor.getValue().getMode());
        verify(memberMapper, org.mockito.Mockito.never()).insert(any(AudienceMemberEntity.class));
    }

    @Test
    void staticAudienceInsertsMembersPerCustomer() {
        assignIdOnInsert(audienceMapper, 8L);

        Map<String, Object> out = action.execute(ctx(), ActionRequest.of(Map.of(
                "name", "定向名单", "member_customer_ids", List.of(101L, 102L)))).data();

        assertEquals("STATIC", out.get("mode"));
        assertEquals(2, out.get("member_count"));
        ArgumentCaptor<AudienceEntity> captor = ArgumentCaptor.forClass(AudienceEntity.class);
        verify(audienceMapper).insert(captor.capture());
        assertNull(captor.getValue().getRule());
        ArgumentCaptor<AudienceMemberEntity> mc = ArgumentCaptor.forClass(AudienceMemberEntity.class);
        verify(memberMapper, times(2)).insert(mc.capture());
        assertEquals(List.of(101L, 102L), mc.getAllValues().stream()
                .map(AudienceMemberEntity::getCustomerId).toList());
        assertEquals(8L, mc.getAllValues().get(0).getAudienceId());
        assertEquals(0, resolveCalls.get());
    }

    private static CustomerEntity customer(long id) {
        CustomerEntity c = new CustomerEntity();
        c.setId(id);
        return c;
    }
}