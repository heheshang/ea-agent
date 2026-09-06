package com.eaagent.ontology.action;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.model.TemplateEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createTemplate：agent 建模板产物必须进入人工审核流（PENDING），
 * 不得直出 APPROVED 绕过审核；路由/发送侧依赖 APPROVED 强校验。
 */
class CreateTemplateActionTest {

    private final TemplateMapper templateMapper = mock(TemplateMapper.class);
    private final CreateTemplateAction action = new CreateTemplateAction(
            mock(ActionLogMapper.class), new IdempotencyService(new StringRedisTemplate()), new StringRedisTemplate(),
            templateMapper);

    private static ActionContext ctx() {
        return ActionContext.of(1L, 2L, "OPERATOR", null);
    }

    @Test
    void createdTemplateEntersPendingReview() {
        when(templateMapper.insert(any(TemplateEntity.class))).thenAnswer(inv -> {
            TemplateEntity t = inv.getArgument(0);
            t.setId(99L);
            return 1;
        });
        ActionResult result = action.execute(ctx(), ActionRequest.of(Map.of(
                "title", "下单回馈-满100减20券",
                "channel", "email",
                "content", "尊贵的 {{name}}，下单满100立减20（订单 {{order_id}}）")));
        Map<String, Object> out = result.data();
        ArgumentCaptor<TemplateEntity> cap = ArgumentCaptor.forClass(TemplateEntity.class);
        verify(templateMapper).insert(cap.capture());
        TemplateEntity inserted = cap.getValue();
        // 审核流必须保留：产物 PENDING 待 REVIEWER 审批，禁止直出 APPROVED
        assertEquals(TemplateEntity.REVIEW_PENDING, inserted.getReviewStatus());
        assertEquals("下单回馈-满100减20券", inserted.getTitle());
        assertEquals(List.of("name", "order_id"), inserted.getVars());
        assertEquals(99L, inserted.getId().longValue());
        assertEquals(Long.valueOf(99L), out.get("template_id"));
        assertEquals(TemplateEntity.REVIEW_PENDING, out.get("review_status"));
        assertEquals(List.of("name", "order_id"), out.get("vars"));
    }

    @Test
    void rejectsUnknownChannel() {
        BizException ex = assertThrows(BizException.class,
                () -> action.execute(ctx(), ActionRequest.of(Map.of(
                        "title", "x", "channel", "fax", "content", "hi"))));
        assertEquals(ErrorCode.ACTION_VALIDATION_FAILED, ex.getErrorCode());
        verify(templateMapper, never()).insert(any(TemplateEntity.class));
    }

    @Test
    void rejectsBlankContent() {
        BizException ex = assertThrows(BizException.class,
                () -> action.execute(ctx(), ActionRequest.of(Map.of(
                        "title", "x", "channel", "email", "content", "  "))));
        assertEquals(ErrorCode.ACTION_VALIDATION_FAILED, ex.getErrorCode());
        verify(templateMapper, never()).insert(any(TemplateEntity.class));
    }
}