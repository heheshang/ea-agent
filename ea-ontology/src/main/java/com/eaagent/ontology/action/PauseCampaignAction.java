package com.eaagent.ontology.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.IdempotencyService;
import com.eaagent.ontology.mapper.ActionLogMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.model.CampaignEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** pauseCampaign（10.4）：暂停运行中的任务；幂等（已 PAUSED 视为成功）。 */
@Component
public class PauseCampaignAction extends AbstractAction {

    private final CampaignMapper campaignMapper;

    public PauseCampaignAction(ActionLogMapper actionLogMapper, IdempotencyService idempotencyService,
                               StringRedisTemplate redis, CampaignMapper campaignMapper) {
        super(actionLogMapper, idempotencyService, redis);
        this.campaignMapper = campaignMapper;
    }

    @Override
    public ActionMeta meta() {
        return ActionMeta.builder()
                .name("pauseCampaign")
                .description("暂停任务")
                .requiredArgs(List.of("campaign_id"))
                .permissions(List.of("REVIEWER"))
                .build();
    }

    @Override
    protected Map<String, Object> doExecute(ActionContext ctx, ActionRequest req) {
        CampaignEntity c = campaignMapper.selectOne(new QueryWrapper<CampaignEntity>()
                .eq(CampaignEntity.COL_TENANT_ID, ctx.tenantId()).eq(CampaignEntity.COL_ID, req.getLong("campaign_id")));
        if (c == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        if ("PAUSED".equals(c.getStatus())) {
            Map<String, Object> out = new HashMap<>();
            out.put("campaign_id", c.getId());
            out.put("status", c.getStatus());
            return out;
        }
        c.setStatus("PAUSED");
        c.setUpdatedAt(Instant.now());
        campaignMapper.updateById(c);
        Map<String, Object> out = new HashMap<>();
        out.put("campaign_id", c.getId());
        out.put("status", c.getStatus());
        return out;
    }
}