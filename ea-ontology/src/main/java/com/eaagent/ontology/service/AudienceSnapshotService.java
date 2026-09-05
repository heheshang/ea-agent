package com.eaagent.ontology.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.CustomerEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 触达人群快照（修复「活动误发全量」）：活动创建/换人群时把圈定人群的成员固化为
 * audience_snapshot，发送只按快照内客户，绝不在发送时实时重算人群。
 * 快照随 campaign 行租户隔离；存量活动（无快照）首次发送时惰性现算并回填。
 */
@Service
@RequiredArgsConstructor
public class AudienceSnapshotService {

    /** 快照键名（也用于 JSON 通道与 Web 展示）。 */
    public static final String KEY_CUSTOMER_IDS = "customer_ids";
    public static final String KEY_MEMBER_COUNT = "member_count";

    private final AudienceMapper audienceMapper;
    private final AudienceResolver audienceResolver;
    private final CampaignMapper campaignMapper;

    /**
     * 构建人群快照：租户限定查 audience（不存在 → OBJECT_NOT_FOUND），按规则现算成员后固化。
     * 创建活动前调用：audience 不存在 / 规则非法在落库前报错，不产生半成品 campaign 行。
     */
    public Map<String, Object> build(Long tenantId, Long audienceId) {
        AudienceEntity a = audienceMapper.selectOne(new QueryWrapper<AudienceEntity>()
                .eq(AudienceEntity.COL_TENANT_ID, tenantId)
                .eq(AudienceEntity.COL_ID, audienceId));
        if (a == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND);
        }
        List<Long> ids = audienceResolver.resolve(tenantId, a).stream()
                .map(CustomerEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("audience_id", a.getId());
        snap.put("audience_name", a.getName());
        snap.put("mode", a.getMode());
        snap.put("rule", a.getRule());
        snap.put(KEY_MEMBER_COUNT, ids.size());
        snap.put(KEY_CUSTOMER_IDS, ids);
        snap.put("snapshot_at", Instant.now().toString());
        return snap;
    }

    /**
     * 取活动待触达成员（发送管线唯一人群来源）：
     * - 有快照（含 member_count=0 的合法空人群）→ 原样返回，不重算；
     * - 无快照（存量活动/seed）→ 按当前人群现算并 updateById 回填 campaign 行（自愈），
     *   此后该活动范围同样固化。
     */
    public List<Long> memberIds(Long tenantId, CampaignEntity c) {
        Map<String, Object> snap = c.getAudienceSnapshot();
        if (snap != null && snap.containsKey(KEY_CUSTOMER_IDS)) {
            return castIds(snap.get(KEY_CUSTOMER_IDS));
        }
        Map<String, Object> built = build(tenantId, c.getAudienceId());
        c.setAudienceSnapshot(built);
        c.setUpdatedAt(Instant.now());
        campaignMapper.updateById(c);
        return castIds(built.get(KEY_CUSTOMER_IDS));
    }

    private static List<Long> castIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(o -> Long.valueOf(String.valueOf(o)))
                .toList();
    }
}