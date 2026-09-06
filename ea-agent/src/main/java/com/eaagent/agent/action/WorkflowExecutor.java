package com.eaagent.agent.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.DeliveryMapper;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.DeliveryEntity;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.service.AudienceSnapshotService;
import com.eaagent.ontology.service.WorkflowCodec;
import com.eaagent.ontology.service.WorkflowConditionEvaluator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多通道编排执行器（campaign.workflow DAG，事件驱动同步执行，v1 同步边界）：
 * 每客户从根节点（无入边）DFS —— 条件评估命中 → 按节点 channel/template 发送 → 投递成功
 * （SENT/DELIVERED）才递归 next；前驱任一非推进态（FAILED/SKIPPED/UNSUBSCRIBED 等）该节点对
 * 该客户不触发（记 SKIPPED）。prev.* 条件按该客户在 (campaign, workflow_node) 的最近投递状态关联
 * （历史投递 + 本次执行中间状态）。visited 防御环（vallidator 已保证无环）。同步完成，跨周期条件 v2。
 */
@Component
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    /** 可推进状态：前驱投递成功 → 后继节点可触发。 */
    private static final Set<String> PROGRESS = Set.of(DeliveryEntity.STATUS_SENT, DeliveryEntity.STATUS_DELIVERED);
    /** 编排内存标记（不进 delivery 表）：条件未命中/前驱未成功 → 该客户该节点不触发。 */
    private static final String SKIPPED = "SKIPPED";

    private final CustomerMapper customerMapper;
    private final DeliveryMapper deliveryMapper;
    private final AudienceSnapshotService snapshotService;
    private final SendTouchAction sendTouchAction;

    public WorkflowExecutor(CustomerMapper customerMapper, DeliveryMapper deliveryMapper,
                            AudienceSnapshotService snapshotService, SendTouchAction sendTouchAction) {
        this.customerMapper = customerMapper;
        this.deliveryMapper = deliveryMapper;
        this.snapshotService = snapshotService;
        this.sendTouchAction = sendTouchAction;
    }

    /**
     * 执行一次事件触发的 DAG 编排。非 DAG 活动（workflow 空）返回 {workflow:false}，由调用方走原管线。
     *
     * @return {workflow:true, campaign_id, total_customers, nodes:[{id,matched,sent,skipped,failed,unsubscribed}], message}
     */
    public Map<String, Object> execute(Long tenantId, CampaignEntity campaign,
                                       String eventType, Map<String, Object> eventPayload) {
        List<Map<String, Object>> nodes = campaign.getWorkflow();
        if (nodes == null || nodes.isEmpty()) {
            return Map.of("workflow", false);
        }
        List<Map<String, Object>> order = WorkflowCodec.dependencyOrder(nodes);
        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        Map<String, List<String>> nexts = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (Map<String, Object> n : order) {
            String id = String.valueOf(n.get("id"));
            nodeById.put(id, n);
            nexts.put(id, WorkflowCodec.nextIds(n));
            indegree.put(id, 0);
        }
        for (List<String> tos : nexts.values()) {
            for (String to : tos) {
                indegree.merge(to, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : order) {
            if (indegree.get(String.valueOf(n.get("id"))) == 0) {
                roots.add(n);
            }
        }

        // 人群 = 活动快照
        List<Long> memberIds = snapshotService.memberIds(tenantId, campaign);
        List<CustomerEntity> customers = memberIds.isEmpty() ? List.of()
                : customerMapper.selectList(new QueryWrapper<CustomerEntity>()
                        .eq(CustomerEntity.COL_TENANT_ID, tenantId).in(CustomerEntity.COL_ID, memberIds));

        // 历史投递状态：每客户每节点最近一次状态（(campaign, workflow_node) 关联）
        Map<Long, Map<String, String>> state = loadHistory(tenantId, campaign.getId(), nodeById.keySet());

        // 节点统计
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (String id : nodeById.keySet()) {
            stats.put(id, new int[5]); // [matched, sent, skipped, failed, unsubscribed]
        }
        Map<Long, Set<String>> visited = new HashMap<>();
        Map<Long, TemplateEntity> tplCache = new HashMap<>();

        for (CustomerEntity c : customers) {
            Set<String> seen = visited.computeIfAbsent(c.getId(), k -> new HashSet<>());
            for (Map<String, Object> root : roots) {
                dfs(tenantId, campaign, c, root, nodeById, nexts, state, seen, stats, eventType, eventPayload, tplCache);
            }
        }

        List<Map<String, Object>> nodeOut = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : nodeById.entrySet()) {
            int[] s = stats.get(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("matched", s[0]);
            m.put("sent", s[1]);
            m.put("skipped", s[2]);
            m.put("failed", s[3]);
            m.put("unsubscribed", s[4]);
            nodeOut.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflow", true);
        out.put("campaign_id", campaign.getId());
        out.put("total_customers", customers.size());
        out.put("nodes", nodeOut);
        out.put("message", "多通道编排已同步执行（v1：事件到达逐节点推进）");
        return out;
    }

    /** 某客户在某节点的 DFS：前驱全推进 → 条件命中 → 发送 → 成功才递归 next。 */
    private void dfs(Long tenantId, CampaignEntity campaign, CustomerEntity c, Map<String, Object> node,
                     Map<String, Map<String, Object>> nodeById, Map<String, List<String>> nexts,
                     Map<Long, Map<String, String>> state, Set<String> seen,
                     Map<String, int[]> stats, String eventType, Map<String, Object> eventPayload,
                     Map<Long, TemplateEntity> tplCache) {
        String id = String.valueOf(node.get("id"));
        if (!seen.add(id)) {
            return; // 防御环/重复路径
        }
        Map<String, String> st = state.computeIfAbsent(c.getId(), k -> new HashMap<>());
        // 前驱检查：任一前驱未成功（含缺失）→ 本节点对客户不触发
        for (String pred : predsOf(nodeById, id, nexts)) {
            String s = st.get(pred);
            if (s == null || !PROGRESS.contains(s)) {
                st.put(id, SKIPPED);
                stats.get(id)[2]++;
                return;
            }
        }
        // 条件评估（null/空 → 恒真）
        if (!WorkflowConditionEvaluator.evaluate((Map<String, Object>) node.get("condition"),
                eventPayload, c, st)) {
            st.put(id, SKIPPED);
            stats.get(id)[2]++;
            return;
        }
        stats.get(id)[0]++; // matched
        String channel = String.valueOf(node.get("channel"));
        Long tplId = node.get("template_id") instanceof Number n
                ? n.longValue() : Long.valueOf(String.valueOf(node.get("template_id")));
        DeliveryEntity d = sendTouchAction.sendOneCustomer(tenantId, campaign, c, channel, tplId,
                id, eventType, eventPayload, tplCache);
        if (d == null) {
            st.put(id, DeliveryEntity.STATUS_UNSUBSCRIBED);
            stats.get(id)[4]++;
            return;
        }
        st.put(id, d.getStatus());
        if (PROGRESS.contains(d.getStatus())) {
            stats.get(id)[1]++;
            for (String to : nexts.get(id)) {
                dfs(tenantId, campaign, c, nodeById.get(to), nodeById, nexts, state, seen,
                        stats, eventType, eventPayload, tplCache);
            }
        } else {
            stats.get(id)[3]++;
        }
    }

    /** 节点前驱：反向扫描 nexts（v1 DAG 规模小，直接遍历可读优先）。 */
    private static List<String> predsOf(Map<String, Map<String, Object>> nodeById, String id,
                                        Map<String, List<String>> nexts) {
        List<String> preds = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : nexts.entrySet()) {
            if (e.getValue().contains(id)) {
                preds.add(e.getKey());
            }
        }
        return preds;
    }

    /** 历史投递加载：每 (customer, workflow_node) 取最近一次（id 降序 putIfAbsent）。 */
    private Map<Long, Map<String, String>> loadHistory(Long tenantId, Long campaignId, Set<String> nodeIds) {
        Map<Long, Map<String, String>> state = new HashMap<>();
        if (nodeIds.isEmpty()) {
            return state;
        }
        List<DeliveryEntity> rows = deliveryMapper.selectList(new QueryWrapper<DeliveryEntity>()
                .eq(DeliveryEntity.COL_TENANT_ID, tenantId)
                .eq(DeliveryEntity.COL_CAMPAIGN_ID, campaignId)
                .in(DeliveryEntity.COL_WORKFLOW_NODE, nodeIds)
                .orderByDesc(DeliveryEntity.COL_ID));
        for (DeliveryEntity d : rows) {
            if (d.getWorkflowNode() == null) {
                continue;
            }
            state.computeIfAbsent(d.getCustomerId(), k -> new HashMap<>())
                    .putIfAbsent(d.getWorkflowNode(), d.getStatus());
        }
        return state;
    }
}