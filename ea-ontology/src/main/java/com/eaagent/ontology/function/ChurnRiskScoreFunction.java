package com.eaagent.ontology.function;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.EventMapper;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.EventEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * churnRiskScore：流失风险预测（4.2 Call Function · 预测模型）。
 * v1 启发式模型：近 30 天事件数（order_placed 等业务信号）衰减 + 客户状态加成，
 * 版本化输出（model 字段）——后续替换真实 ML 模型时口径可区分，调用方不感知。
 * 仅决策咨询：召回触达等动作仍走 applyAction（Action 管线强制频控/退订校验）。
 */
@Component
public class ChurnRiskScoreFunction implements Function {

    /** 预测窗口：近 30 天。 */
    private static final int WINDOW_DAYS = 30;
    /** 无事件基准风险。 */
    private static final double BASE_SCORE = 0.9;
    /** 每 1 个事件的风险衰减（5 个事件后触底 0.1）。 */
    private static final double EVENT_DECAY = 0.2;
    /** 非 ACTIVE 状态加成（上限 0.95）。 */
    private static final double INACTIVE_PENALTY = 0.15;

    private final CustomerMapper customerMapper;
    private final EventMapper eventMapper;

    public ChurnRiskScoreFunction(CustomerMapper customerMapper, EventMapper eventMapper) {
        this.customerMapper = customerMapper;
        this.eventMapper = eventMapper;
    }

    @Override
    public String name() {
        return "churnRiskScore";
    }

    @Override
    public String description() {
        return "流失风险预测：返回客户流失风险分 0-1 与等级（模型 v1-heuristic：近30天事件衰减 + 状态加成）";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("customer_id", Map.of("type", "integer", "description", "客户 ID"));
    }

    @Override
    public Map<String, Object> execute(long tenantId, Map<String, Object> args) {
        long cid = FunctionArgs.requireLong(args, "customer_id");
        // 租户归属校验（6.3）：id 必须属于当前租户，否则拒绝
        CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                .eq(CustomerEntity.COL_TENANT_ID, tenantId).eq(CustomerEntity.COL_ID, cid));
        if (c == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "customer not found: " + cid);
        }
        Instant since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
        Long events30 = eventMapper.selectCount(new QueryWrapper<EventEntity>()
                .eq(EventEntity.COL_TENANT_ID, tenantId)
                .eq(EventEntity.COL_CUSTOMER_ID, cid)
                .ge(EventEntity.COL_CREATED_AT, since));
        long n = events30 == null ? 0L : events30;

        double score = n == 0 ? BASE_SCORE : Math.max(0.1, 1.0 - n * EVENT_DECAY);
        String status = c.getStatus();
        if (status != null && !CustomerEntity.STATUS_ACTIVE.equals(status)) {
            score = Math.min(0.95, score + INACTIVE_PENALTY);
        }
        score = Math.round(score * 100) / 100.0;
        String level = score >= 0.7 ? "HIGH" : score >= 0.4 ? "MEDIUM" : "LOW";
        return Map.of("customer_id", cid, "score", score, "level", level, "model", "v1-heuristic",
                "events_30d", n, "status", status == null ? "" : status,
                "basis", "近30天事件数 " + n + "（每单衰减0.2，无事件0.9）" + (!CustomerEntity.STATUS_ACTIVE.equals(status) ? " + 非ACTIVE状态加成0.15" : ""));
    }
}