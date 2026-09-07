-- V17: 新建聊天（Chat）——聊天容器 + 调用链可追踪锚点
-- 需求：新建聊天产出唯一 id + 描述；该 id 可在 Ontology 调用链（agent_tool_call 明细 / run-trace）中追踪。
-- 设计：
--   * agent_chat：聊天容器（id 全局唯一、tenant 内唯一），描述默认「新对话」、首条 goal 到来时自动更新为 goal 截断；
--   * agent_run.chat_id：run 归属聊天（旧行 NULL，向后兼容）；
--   * agent_tool_call.chat_id：调用链明细直接携带聊天 id（追加型审计表，沿用 V5 无 FK 约定）。

CREATE TABLE agent_chat (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   bigint NOT NULL REFERENCES tenant(id),
  user_id     bigint NOT NULL,
  description varchar(256) NOT NULL,
  status      varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at  timestamptz  NOT NULL DEFAULT now(),
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  CONSTRAINT fk_agent_chat_user FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user(tenant_id, id)
);

ALTER TABLE agent_run ADD COLUMN chat_id bigint;

ALTER TABLE agent_tool_call ADD COLUMN chat_id bigint;

CREATE INDEX idx_agent_chat_tenant ON agent_chat (tenant_id, user_id, created_at DESC);
CREATE INDEX idx_agent_run_chat    ON agent_run (tenant_id, chat_id, created_at DESC);
CREATE INDEX idx_agent_tool_call_chat ON agent_tool_call (tenant_id, chat_id, seq);

ALTER TABLE agent_run ADD CONSTRAINT fk_agent_run_chat FOREIGN KEY (tenant_id, chat_id) REFERENCES agent_chat(tenant_id, id);