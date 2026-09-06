-- agentscope 持久化落库（V16）：agentscope 运行期两类存储迁移到 PostgreSQL。
-- 背景：默认 agentscope 把会话状态与 workspace 文件落在本地文件系统（.agentscope/workspace），
-- 多副本/多实例时状态不共享，且跨租户会话存在串扰风险。本迁移引入两张表承载
-- AgentStateStore（会话状态 KV）与 BaseStore（workspace 文件 KV，RemoteFilesystem 后端）。
--
-- 多租户语义（重点）：
--   1. AgentStateStore 接口以 (userId, sessionId, key) 定位——引擎侧把租户编码进 userId
--      （"tenant-{tenantId}"），故表中显式落 tenant_id 列 + FK 兜底，不依赖任何 ThreadLocal；
--   2. BaseStore 接口无租户参数，租户从 namespace 的 "users" 段后解析（namespace 结构
--      ["agents", agentId, "users", userId, route]），解析结果落 tenant_id 列；
--   3. 两条唯一键均含 tenant_id：跨租户同 (session, key) / (namespace, key) 互不覆盖。
-- 显式租户列 + 复合唯一键是本项目多租户基线（对齐 V1 注释：不用租户插件，列 + FK 兜底）。

-- AgentStateStore 会话状态：单值/列表统一存 jsonb（slot_kind 区分语义；列表全量替换，
-- 对齐 InMemoryAgentStateStore 契约，不做 JSONL 增量追加）。
CREATE TABLE agent_scope_state (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id  bigint NOT NULL REFERENCES tenant(id),
  user_id    varchar(128) NOT NULL,
  session_id varchar(128) NOT NULL,
  state_key  varchar(128) NOT NULL,
  slot_kind  varchar(8)  NOT NULL,
  content    jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, session_id, state_key),
  CONSTRAINT chk_agent_scope_state_slot_kind CHECK (slot_kind IN ('single', 'list'))
);

CREATE INDEX idx_agent_scope_state_tenant ON agent_scope_state (tenant_id);
CREATE INDEX idx_agent_scope_state_session ON agent_scope_state (tenant_id, user_id, session_id);

-- BaseStore workspace 文件：namespace 以 \u001F（单位分隔符）join 存文本，前缀搜索按段匹配
-- （search(namespace, limit, offset) 语义 = namespace 前缀 + key 排序分页，对齐 InMemoryStore）。
CREATE TABLE agent_scope_file (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id  bigint NOT NULL REFERENCES tenant(id),
  namespace  text NOT NULL,
  item_key   text NOT NULL,
  value      jsonb NOT NULL,
  version    bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, namespace, item_key)
);

CREATE INDEX idx_agent_scope_file_tenant ON agent_scope_file (tenant_id);
CREATE INDEX idx_agent_scope_file_namespace ON agent_scope_file (tenant_id, namespace);