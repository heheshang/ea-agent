package com.eaagent.app.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.mapper.EventMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.EventEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

/**
 * 存留看板（/api/retention）：触达客户回访留存分析。
 *
 * 口径：
 *  - 触达 = delivery.status='SENT'（通道侧成功送出；回执仅推进到 DELIVERED，不改变触达基准）。
 *  - 队列 = 窗口内每位客户「首次成功触达」所在日（客户去重取最早一次；可选按 campaign 过滤）。
 *  - 回访 = 触达后 N 天内产生 ≥1 条目标业务事件（event_type 可选过滤，默认全部事件）。
 *  - D1/D3/D7/D30 为累计口径（触达后 1/3/7/30×24h 内回访客户占队列比），与「第 N 日留存」区分。
 *
 * 时效与伸缩：窗口统一 UTC（与 agent 统计一致）；行级全量加载后 Java 聚合
 * （与 AgentStatsService 同模式，规模上去后再下沉 SQL）。
 */
@Service
public class RetentionService {

    /** 回访窗口（天，累计口径）。 */
    private static final int[] WINDOW_DAYS = {1, 3, 7, 30};
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final DeliveryMapper deliveryMapper;
    private final EventMapper eventMapper;
    private final CampaignMapper campaignMapper;

    public RetentionService(DeliveryMapper deliveryMapper, EventMapper eventMapper,
                            CampaignMapper campaignMapper) {
        this.deliveryMapper = deliveryMapper;
        this.eventMapper = eventMapper;
        this.campaignMapper = campaignMapper;
    }

    public Map<String, Object> retention(long tenantId, int days, Long campaignId, String eventType) {
        Instant start = Instant.now().minus(days, ChronoUnit.DAYS);

        // 1. 触达行（SENT，可选 campaign）与业务事件（全量载入：回访判定在 Java 侧按 eventType 过滤，
        //    同时用全量类型生成筛选项——避免过滤选择框随当前筛选消失）。
        List<DeliveryEntity> deliveries = deliveryMapper.selectList(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_TENANT_ID, tenantId)
                .eq(DeliveryEntity.COL_STATUS, "SENT")
                .ge(DeliveryEntity.COL_CREATED_AT, start)
                .eq(campaignId != null, DeliveryEntity.COL_CAMPAIGN_ID, campaignId));
        List<EventEntity> events = eventMapper.selectList(new QueryWrapper<EventEntity>()
                .eq(EventEntity.COL_TENANT_ID, tenantId)
                .ge(EventEntity.COL_CREATED_AT, start));

        Map<Long, String> campaignNames = campaignMapper.selectList(new QueryWrapper<CampaignEntity>()
                        .eq(CampaignEntity.COL_TENANT_ID, tenantId)).stream()
                .collect(Collectors.toMap(CampaignEntity::getId, CampaignEntity::getName, (a, b) -> a));

        // 2. 首次触达（客户去重，取最早）
        Map<Long, Instant> firstTouch = new HashMap<>();
        for (DeliveryEntity d : deliveries) {
            firstTouch.merge(d.getCustomerId(), d.getCreatedAt(),
                    BinaryOperator.minBy(Comparator.naturalOrder()));
        }

        // 3. 回访事件（客户维度；窗口起算 >= 查询起点，事件必晚于首触达才有效，用时间比较）
        Map<Long, List<Instant>> customerEvents = new HashMap<>();
        for (EventEntity e : events) {
            if (e.getCustomerId() == null) {
                continue;
            }
            if (eventType != null && !eventType.isBlank() && !eventType.equals(e.getEventType())) {
                continue;
            }
            customerEvents.computeIfAbsent(e.getCustomerId(), k -> new ArrayList<>()).add(e.getCreatedAt());
        }

        // 4. 逐客户算回访窗口 → 按触达日聚合
        TreeMap<LocalDate, CohortAgg> byDay = new TreeMap<>();
        int returnCustomers = 0;
        double hoursSum = 0;
        for (Map.Entry<Long, Instant> en : firstTouch.entrySet()) {
            Instant ft = en.getValue();
            LocalDate day = ft.atZone(UTC).toLocalDate();
            CohortAgg agg = byDay.computeIfAbsent(day, k -> new CohortAgg());
            agg.base++;
            Instant firstReturn = firstReturnAfter(customerEvents.get(en.getKey()), ft);
            if (firstReturn == null) {
                continue;
            }
            double hours = Duration.between(ft, firstReturn).toMinutes() / 60.0;
            hoursSum += hours;
            returnCustomers++;
            for (int i = 0; i < WINDOW_DAYS.length; i++) {
                if (hours <= WINDOW_DAYS[i] * 24.0) {
                    agg.windows[i]++;
                }
            }
        }

        // 5. 汇总 + 队列表
        int baseTotal = firstTouch.size();
        int[] windowTotals = new int[WINDOW_DAYS.length];
        byDay.values().forEach(a -> {
            for (int i = 0; i < WINDOW_DAYS.length; i++) {
                windowTotals[i] += a.windows[i];
            }
        });

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cohort_customers", baseTotal);
        summary.put("deliveries", deliveries.size());
        summary.put("target_events", customerEvents.values().stream().mapToInt(List::size).sum());
        summary.put("return_customers", returnCustomers);
        summary.put("avg_return_hours", returnCustomers > 0 ? round1(hoursSum / returnCustomers) : 0);
        for (int i = 0; i < WINDOW_DAYS.length; i++) {
            int key = WINDOW_DAYS[i];
            summary.put("d" + key + "_rate", rate(windowTotals[i], baseTotal));
            summary.put("d" + key + "_count", windowTotals[i]);
        }

        List<Map<String, Object>> cohorts = new ArrayList<>();
        for (Map.Entry<LocalDate, CohortAgg> en : byDay.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", en.getKey().toString());
            CohortAgg a = en.getValue();
            row.put("base", a.base);
            for (int i = 0; i < WINDOW_DAYS.length; i++) {
                int key = WINDOW_DAYS[i];
                row.put("d" + key, a.windows[i]);
                row.put("d" + key + "_rate", rate(a.windows[i], a.base));
            }
            cohorts.add(row);
        }

        // 6. 活动维（该活动内的首次触达队列；未关联活动的 delivery 不参与）
        List<Map<String, Object>> campaigns = campaignBreakdown(deliveries, customerEvents, campaignNames);

        // 7. 可筛事件类型（租户全量去重，保持选择框稳定）
        List<String> eventTypes = events.stream()
                .map(EventEntity::getEventType)
                .filter(t -> t != null && !t.isBlank())
                .distinct().sorted().toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("cohorts", cohorts);
        result.put("campaigns", campaigns);
        result.put("event_types", eventTypes);
        return result;
    }

    /** 活动维留存：每活动内客户首触达队列 × 回访窗口；按客户数降序。 */
    private List<Map<String, Object>> campaignBreakdown(List<DeliveryEntity> deliveries,
                                                        Map<Long, List<Instant>> customerEvents,
                                                        Map<Long, String> campaignNames) {
        Map<Long, List<DeliveryEntity>> byCampaign = new LinkedHashMap<>();
        for (DeliveryEntity d : deliveries) {
            if (d.getCampaignId() != null) {
                byCampaign.computeIfAbsent(d.getCampaignId(), k -> new ArrayList<>()).add(d);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, List<DeliveryEntity>> en : byCampaign.entrySet()) {
            Map<Long, Instant> first = new HashMap<>();
            for (DeliveryEntity d : en.getValue()) {
                first.merge(d.getCustomerId(), d.getCreatedAt(),
                        BinaryOperator.minBy(Comparator.naturalOrder()));
            }
            int[] windows = new int[WINDOW_DAYS.length];
            for (Map.Entry<Long, Instant> ce : first.entrySet()) {
                Instant fr = firstReturnAfter(customerEvents.get(ce.getKey()), ce.getValue());
                if (fr == null) {
                    continue;
                }
                double hours = Duration.between(ce.getValue(), fr).toMinutes() / 60.0;
                for (int i = 0; i < WINDOW_DAYS.length; i++) {
                    if (hours <= WINDOW_DAYS[i] * 24.0) {
                        windows[i]++;
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("campaign_id", en.getKey());
            row.put("name", campaignNames.getOrDefault(en.getKey(), "未关联活动"));
            row.put("touches", en.getValue().size());
            row.put("customers", first.size());
            for (int i = 0; i < WINDOW_DAYS.length; i++) {
                int key = WINDOW_DAYS[i];
                row.put("d" + key + "_rate", rate(windows[i], first.size()));
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> r) -> ((Number) r.get("customers")).intValue()).reversed());
        return rows;
    }

    private static Instant firstReturnAfter(List<Instant> eventTimes, Instant touch) {
        if (eventTimes == null) {
            return null;
        }
        Instant first = null;
        for (Instant t : eventTimes) {
            if (t.isAfter(touch) && (first == null || t.isBefore(first))) {
                first = t;
            }
        }
        return first;
    }

    private static double rate(int n, int base) {
        return base > 0 ? round4((double) n / base) : 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    /** 单日队列聚合。 */
    private static final class CohortAgg {
        int base;
        final int[] windows = new int[WINDOW_DAYS.length];
    }
}