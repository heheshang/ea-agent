package com.eaagent.ontology.function;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.ontology.mapper.AudienceMemberMapper;
import com.eaagent.ontology.model.AudienceMemberEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * audienceStats：人群规模统计（4.2 Call Function，决策咨询）。
 * 自原有同名工具收编：行为一致，按租户过滤。
 */
@Component
public class AudienceStatsFunction implements Function {

    private final AudienceMemberMapper audienceMemberMapper;

    public AudienceStatsFunction(AudienceMemberMapper audienceMemberMapper) {
        this.audienceMemberMapper = audienceMemberMapper;
    }

    @Override
    public String name() {
        return "audienceStats";
    }

    @Override
    public String description() {
        return "人群规模统计：返回 audience_id 的成员数量";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("audience_id", Map.of("type", "integer", "description", "人群包 ID"));
    }

    @Override
    public Map<String, Object> execute(long tenantId, Map<String, Object> args) {
        long id = FunctionArgs.requireLong(args, "audience_id");
        Long memberCount = audienceMemberMapper.selectCount(new QueryWrapper<AudienceMemberEntity>()
                .eq(AudienceMemberEntity.COL_TENANT_ID, tenantId)
                .eq(AudienceMemberEntity.COL_AUDIENCE_ID, id));
        return Map.of("audience_id", id, "member_count", memberCount == null ? 0L : memberCount);
    }
}