-- knowledge 知识库表（V6）：租户级静态业务知识条目。
-- Agent 对话（AgentscopeAgentEngine）每轮按相关度检索 topK 条目注入上下文（RAG 式），
-- 检索为内存关键词加权打分（标题 +3 / 标签 +2 / 内容 +1，子串包含累加，阈值 >=2），无向量依赖。
-- 约定：行级隔离靠显式 tenant_id（无租户插件）；无 deleted 列 → 物理删除。
CREATE TABLE knowledge (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  title         varchar(256) NOT NULL,
  content       text NOT NULL,
  tags          jsonb NOT NULL DEFAULT '[]'::jsonb,
  enabled       boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id)
);

-- 租户维度启用列表 + 最近更新的检索加载
CREATE INDEX idx_knowledge_tenant_enabled ON knowledge (tenant_id, enabled, updated_at DESC);