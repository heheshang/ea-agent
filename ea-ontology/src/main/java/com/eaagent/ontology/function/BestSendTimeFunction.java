package com.eaagent.ontology.function;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.mapper.EventMapper;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.EventEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * bestSendTime：最优发送时段推荐（4.2 Call Function · 优化算法）。
 * v1 启发式优化：按客户近 30 天业务事件（客户主动行为，权重 1.0）与成功触达
 * （验证过可触达的时段，权重 0.6）的时段分布打分，取最高分小时为推荐窗口；
 * 合规约束：回避 23-06 时安静窗口；无信号时回退默认业务时段 10-11 时。
 * 仅决策咨询：实际发送时段约束由 SendTouchAction 内置校验（H）。
 */
@Component
public class BestSendTimeFunction implements Function {

    /** 信号窗口：近 30 天。 */
    private static final int WINDOW_DAYS = 30;
    /** 事件（客户主动行为）权重。 */
    private static final double EVENT_WEIGHT = 1.0;
    /** 成功触达权重。 */
    private static final double SENT_WEIGHT = 0.6;
    /** 安静窗口：23-06 时（合规 AVOID 段，有其余信号时回避）。 */
    private static final int QUIET_START = 23;
    private static final int QUIET_END = 6;
    /** 无信号回退时段。 */
    private static final int FALLBACK_HOUR = 10;

    private final CustomerMapper customerMapper;
    private final EventMapper eventMapper;
    private final DeliveryMapper deliveryMapper;

    public BestSendTimeFunction(CustomerMapper customerMapper, EventMapper eventMapper, DeliveryMapper deliveryMapper) {
        this.customerMapper = customerMapper;
        this.eventMapper = eventMapper;
        this.deliveryMapper = deliveryMapper;
    }

    @Override
    public String name() {
        return "bestSendTime";
    }

    @Override
    public String description() {
        return "最优发送时段：基于客户近30天事件与成功触达的时段分布，推荐最佳发送小时窗口与置信度（含合规回避）";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("customer_id", Map.of("type", "integer", "description", "客户 ID"));
    }

    @Override
    public Map<String, Object> execute(long tenantId, Map<String, Object> args) {
        long cid = FunctionArgs.requireLong(args, "customer_id");
        // 租户归属校验（6.3）
        CustomerEntity c = customerMapper.selectOne(new QueryWrapper<CustomerEntity>()
                .eq(CustomerEntity.COL_TENANT_ID, tenantId).eq(CustomerEntity.COL_ID, cid));
        if (c == null) {
            throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "customer not found: " + cid);
        }
        Instant since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
        List<EventEntity> events = eventMapper.selectList(new QueryWrapper<EventEntity>()
                .eq(EventEntity.COL_TENANT_ID, tenantId)
                .eq(EventEntity.COL_CUSTOMER_ID, cid)
                .ge(EventEntity.COL_CREATED_AT, since));
        List<DeliveryEntity> sent = deliveryMapper.selectList(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_TENANT_ID, tenantId)
                .eq(DeliveryEntity.COL_CUSTOMER_ID, cid)
                .eq(DeliveryEntity.COL_STATUS, DeliveryEntity.STATUS_SENT)
                .ge(DeliveryEntity.COL_CREATED_AT, since));

        double[] score = new double[24];
        for (EventEntity e : events) {
            score[hourOf(e.getCreatedAt())] += EVENT_WEIGHT;
        }
        for (DeliveryEntity d : sent) {
            score[hourOf(d.getCreatedAt())] += SENT_WEIGHT;
        }
        double total = 0;
        for (double s : score) {
            total += s;
        }

        int best = FALLBACK_HOUR;
        boolean hasSignal = total > 0;
        if (hasSignal) {
            best = argMax(score);
            if (isQuiet(best)) {
                int alt = argMaxNonQuiet(score);
                if (alt >= 0 && score[alt] > 0) {
                    best = alt;
                }
            }
        }
        double confidence = hasSignal ? Math.round(score[best] / total * 100) / 100.0 : 0.0;
        List<Map<String, Object>> topSlots = new ArrayList<>();
        if (hasSignal) {
            int[] order = IntStream.range(0, 24)
                    .boxed()
                    .sorted(Comparator.comparingDouble((Integer h) -> score[h]).reversed().thenComparingInt(h -> h))
                    .mapToInt(Integer::intValue)
                    .toArray();
            for (int i = 0; i < Math.min(3, order.length); i++) {
                int h = order[i];
                if (score[h] <= 0) {
                    break;
                }
                topSlots.add(Map.of("hour", h, "slot", slot(h), "score", round1(score[h])));
            }
        }
        return Map.of("customer_id", cid, "best_slot", slot(best), "hour", best,
                "confidence", confidence, "top_slots", topSlots,
                "signal", Map.of("events_30d", events.size(), "sent_deliveries_30d", sent.size()),
                "model", "v1-hourly-distribution",
                "note", "加权：事件1.0/成功触达0.6；23-06时安静窗口回避；无信号回退10-11时");
    }

    private static int hourOf(Instant t) {
        return t.atZone(ZoneId.systemDefault()).getHour();
    }

    private static boolean isQuiet(int hour) {
        return hour >= QUIET_START || hour <= QUIET_END;
    }

    private static int argMax(double[] score) {
        int best = 0;
        for (int i = 1; i < score.length; i++) {
            if (score[i] > score[best]) {
                best = i;
            }
        }
        return best;
    }

    /** 非安静窗口内的最高分小时（无信号返回 10 作为默认）。 */
    private static int argMaxNonQuiet(double[] score) {
        int best = FALLBACK_HOUR;
        for (int i = 0; i < 24; i++) {
            if (!isQuiet(i) && score[i] > score[best]) {
                best = i;
            }
        }
        return best;
    }

    private static String slot(int hour) {
        return String.format("%02d:00-%02d:00", hour, (hour + 1) % 24);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}