-- 同一租户、用户和会话最多一个在途 run；应用层查询仅提供快速拒绝，唯一索引兜住并发创建。
-- 升级时保留心跳最新的在途行，将历史重复行终结；锁住写入，避免清理与建索引之间再插入重复。
LOCK TABLE agent_run IN SHARE ROW EXCLUSIVE MODE;

WITH ranked AS (
  SELECT id,
         row_number() OVER (
           PARTITION BY tenant_id, user_id, session_id
           ORDER BY updated_at DESC, id DESC
         ) AS position
  FROM agent_run
  WHERE status IN ('NEW', 'PLANNING', 'EXECUTING', 'OBSERVING')
)
UPDATE agent_run AS run
SET status = CASE WHEN run.status = 'NEW' THEN 'CANCELLED' ELSE 'FAILED' END,
    updated_at = now()
FROM ranked
WHERE run.id = ranked.id AND ranked.position > 1;

CREATE UNIQUE INDEX uq_agent_run_inflight_owner_session
ON agent_run (tenant_id, user_id, session_id)
WHERE status IN ('NEW', 'PLANNING', 'EXECUTING', 'OBSERVING');
