-- agent_tool_call 调用链明细表（V5）：
--   每次 run 的工具调用顺序明细（引擎完成时与 agent_run.tool_calls 同源批量写入），
--   供「真实调用链路」回放/审计：seq = run 内调用序号（ToolResultEnd 完成顺序）、
--   kind = tool|action|function（target 非空时 = action/function 名，取自调用入参解析）、
--   args 为参数 JSON 摘要（与 tool_calls.params 同源截断）；run_id 不建 FK（明细由引擎
--   写入，run 行必然先存在；与 agent_run 复合 FK 需 UNIQUE(tenant_id,id)，此处从简）。
-- 旧 run 无明细行（JSONB tool_calls 保留兼容，统计/回放按「无明细」处理）。
CREATE TABLE agent_tool_call (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  run_id        bigint NOT NULL,
  seq           int  NOT NULL,
  kind          varchar(16) NOT NULL,
  name          varchar(128) NOT NULL,
  target        varchar(128),
  args          text,
  duration_ms   int,
  ok            boolean NOT NULL DEFAULT true,
  error         varchar(512),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_tool_call_run  ON agent_tool_call (tenant_id, run_id, seq);
CREATE INDEX idx_agent_tool_call_time ON agent_tool_call (tenant_id, created_at);