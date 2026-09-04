-- agent_run.summary：模型最终回复摘要（会话记忆 Step 2 注入材料）
-- 完成时由 AgentscopeAgentEngine 回写（600 字截断）；旧行 NULL 兼容
ALTER TABLE agent_run ADD COLUMN summary text;