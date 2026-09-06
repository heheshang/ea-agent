-- campaign 工作流编排（V13，多通道条件分支 DAG）：
-- 活动内嵌 workflow jsonb —— 节点数组，每节点 {id, channel, template_id, condition, next}；
-- 事件到达时由 WorkflowExecutor 逐节点同步执行（条件分支 + 多通道，见详细设计 Trigger 链路）。
-- 示例：[{"id":"n1","channel":"sms","template_id":2,"condition":null,"next":["n2"]},
--        {"id":"n2","channel":"email","template_id":4,"condition":{"prev":{"n1":{"op":"eq","value":"DELIVERED"}},"customer":{"status":"ACTIVE"}},"next":[]}]
-- 列可空：非 DAG 活动（单通道单模板）不填。
ALTER TABLE campaign
  ADD COLUMN workflow jsonb;

-- delivery 投递实例标记来源节点（DAG 节点 id；非 DAG 为 null），投递日志展示与 prev.* 条件按 (campaign, customer, workflow_node) 关联。
ALTER TABLE delivery
  ADD COLUMN workflow_node varchar(64);