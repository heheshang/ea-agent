package com.eaagent.ontology.service;

import com.eaagent.common.BizException;
import com.eaagent.common.Channels;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.JsonUtils;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.model.TemplateEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活动多通道编排工作流（workflow jsonb）规范与校验：
 * 节点数组，每节点 {@code {"id":"n1","channel":"sms","template_id":2,"condition":{...},"next":["n2"]}}。
 * - id：非空字符串、全局唯一；
 * - channel ∈ sms|email|wechat|push|console；template_id 必须租户内存在；
 * - next 引用的节点必须存在；整体无环（含自环）；
 * - condition：顶层分组 event / customer / prev，组内 attr→规则（标量=等于，或 {op,value}，op 见
 *   {@link WorkflowConditionEvaluator#OPS}）；prev 组的键必须是存在的其他节点 id。
 * 空/不配（null、空列表）均视为「非 DAG 活动」（单通道单模板），不参与校验。
 */
public final class WorkflowCodec {

    private WorkflowCodec() {
    }

    /** 归一化原始输入（String JSON 或 List）→ 节点列表；空/非法空 → null。 */
    public static List<Map<String, Object>> normalize(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            List<Map<String, Object>> parsed = parseList(s);
            return parsed == null || parsed.isEmpty() ? null : parsed;
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> nodes = new ArrayList<>(list.size());
            for (Object o : list) {
                if (!(o instanceof Map<?, ?>)) {
                    throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow 节点必须是对象");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                nodes.add(m);
            }
            return nodes;
        }
        throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow 必须是节点数组");
    }

    /**
     * 校验（保存路径调用）：结构 + 通道 + 模板存在 + next 引用 + 无环（含条件引用）。
     * 返回依赖拓扑序（供执行器直接使用）；不配（null/空）返回 null。
     */
    public static List<Map<String, Object>> validate(Long tenantId, Object raw, TemplateMapper templateMapper) {
        List<Map<String, Object>> nodes = normalize(raw);
        if (nodes == null) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> n : nodes) {
            Object id = n.get("id");
            if (id == null || String.valueOf(id).isBlank()) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow 节点缺少 id");
            }
            if (!ids.add(String.valueOf(id))) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow 节点 id 重复: " + id);
            }
        }
        Set<String> known = new HashSet<>(ids);
        for (Map<String, Object> n : nodes) {
            String id = String.valueOf(n.get("id"));
            String channel = String.valueOf(n.get("channel"));
            if (!Channels.ALL_SET.contains(channel)) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                        "workflow 节点 " + id + " 未知通道（应为 sms|email|wechat|push|console）: " + channel);
            }
            Object tplId = n.get("template_id");
            if (tplId == null) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow 节点 " + id + " 缺少 template_id");
            }
            TemplateEntity t = templateMapper.selectOne(new QueryWrapper<TemplateEntity>()
                    .eq(TemplateEntity.COL_TENANT_ID, tenantId)
                    .eq(TemplateEntity.COL_ID, toLong(tplId)));
            if (t == null) {
                throw new BizException(ErrorCode.OBJECT_NOT_FOUND, "workflow 节点 " + id + " 模板不存在: " + tplId);
            }
            // next 引用存在 + 不自环
            Object next = n.get("next");
            if (next != null) {
                List<?> nexts = next instanceof List<?> l ? l : parseList(String.valueOf(next));
                if (nexts != null) {
                    for (Object o : nexts) {
                        String to = String.valueOf(o);
                        if (!known.contains(to)) {
                            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                                    "workflow 节点 " + id + " next 引用不存在的节点: " + to);
                        }
                        if (to.equals(id)) {
                            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                                    "workflow 节点自环不允许: " + id);
                        }
                    }
                }
            }
            validateCondition(id, n.get("condition"), known);
        }
        // 整体无环（含多节点环）：Kahn 拓扑排序，环 → 校验失败（E-13002）
        dependencyOrder(nodes);
        return nodes;
    }

    /** 条件结构校验：顶层分组 event|customer|prev；规则 op 合法；prev 引用存在的其他节点。 */
    private static void validateCondition(String nodeId, Object rawCond, Set<String> known) {
        if (rawCond == null) {
            return;
        }
        if (!(rawCond instanceof Map<?, ?>)) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow 节点 " + nodeId + " condition 必须是对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cond = (Map<String, Object>) rawCond;
        for (Map.Entry<String, Object> en : cond.entrySet()) {
            String group = en.getKey();
            if (!WorkflowConditionEvaluator.GROUPS.contains(group)) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                        "workflow 节点 " + nodeId + " condition 分组应为 event|customer|prev: " + group);
            }
            Object rules = en.getValue();
            if (rules == null || !(rules instanceof Map<?, ?>)) {
                throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                        "workflow 节点 " + nodeId + " condition." + group + " 必须是 attr→规则 对象");
            }
            if ("prev".equals(group)) {
                for (Object key : ((Map<?, ?>) rules).keySet()) {
                    String ref = String.valueOf(key);
                    if (!known.contains(ref) || ref.equals(nodeId)) {
                        throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                                "workflow 节点 " + nodeId + " condition.prev 引用的节点必须存在且非自身: " + ref);
                    }
                }
            }
            for (Object v : ((Map<?, ?>) rules).values()) {
                if (v instanceof Map<?, ?>) {
                    String op = String.valueOf(((Map<?, ?>) v).get("op"));
                    if (!WorkflowConditionEvaluator.OP_SET.contains(op)) {
                        throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED,
                                "workflow 节点 " + nodeId + " 条件 op 应为 " + WorkflowConditionEvaluator.OPS + ": " + op);
                    }
                }
            }
        }
    }

    /**
     * Kahn 拓扑排序（按 next 边）；环 → 抛校验失败（执行前防御重算，保存路径已校验）。
     * 返回执行序节点列表（无环前提下与声明序一致即可，稳定性：同层保持声明序）。
     */
    public static List<Map<String, Object>> dependencyOrder(List<Map<String, Object>> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        for (Map<String, Object> n : nodes) {
            indegree.put(String.valueOf(n.get("id")), 0);
        }
        for (Map<String, Object> n : nodes) {
            String id = String.valueOf(n.get("id"));
            List<String> nexts = nextIds(n);
            out.put(id, nexts);
            for (String to : nexts) {
                indegree.merge(to, 1, Integer::sum);
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<Map<String, Object>> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            if (!done.add(id)) {
                continue;
            }
            for (Map<String, Object> n : nodes) {
                if (String.valueOf(n.get("id")).equals(id)) {
                    order.add(n);
                    break;
                }
            }
            for (String to : out.getOrDefault(id, List.of())) {
                if (indegree.merge(to, -1, Integer::sum) == 0) {
                    ready.add(to);
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow 存在环，不允许");
        }
        return order;
    }

    /** 节点 next 引用列表（缺失/空 → 空列表）。 */
    public static List<String> nextIds(Map<String, Object> node) {
        Object next = node.get("next");
        if (next == null) {
            return List.of();
        }
        List<?> list = next instanceof List<?> l ? l : parseList(String.valueOf(next));
        if (list == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(list.size());
        for (Object o : list) {
            ids.add(String.valueOf(o));
        }
        return ids;
    }

    /** 节点前驱（入边）映射：id → 前驱 id 列表。null/空 → 空 map。 */
    public static Map<String, List<String>> predecessors(List<Map<String, Object>> nodes) {
        Map<String, List<String>> preds = new HashMap<>();
        if (nodes == null || nodes.isEmpty()) {
            return preds;
        }
        for (Map<String, Object> n : nodes) {
            preds.put(String.valueOf(n.get("id")), new ArrayList<>());
        }
        for (Map<String, Object> n : nodes) {
            String id = String.valueOf(n.get("id"));
            for (String to : nextIds(n)) {
                preds.computeIfAbsent(to, k -> new ArrayList<>()).add(id);
            }
        }
        return preds;
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(o));
    }

    private static List<Map<String, Object>> parseList(String json) {
        try {
            return JsonUtils.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.ACTION_VALIDATION_FAILED, "workflow JSON 解析失败: " + e.getMessage());
        }
    }
}