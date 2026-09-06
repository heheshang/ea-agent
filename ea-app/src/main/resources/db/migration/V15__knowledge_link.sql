-- 知识库关系边（V15）：类型化关系图谱，与 knowledge 本体节点组成知识图。
-- 语义（对齐 V14 本体化「节点=条目，边=关系」）：
--   relation_type 关系类型：related(相关) / supports(支撑) / refines(细化) / conflicts(冲突)；
--   supersedes(取代) 仍落在 knowledge.supersedes_id（既有取代链 + 生命周期联动语义），本表不含——图谱查询时合并两者。
-- 删节点时边随 knowledge FK ON DELETE CASCADE 自动清空，不留悬空边；同租户同向同类型不重复。
CREATE TABLE knowledge_link (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  source_id     bigint NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
  target_id     bigint NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
  relation_type varchar(16) NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, source_id, target_id, relation_type),
  CONSTRAINT chk_knowledge_link_not_self CHECK (source_id <> target_id)
);

CREATE INDEX idx_knowledge_link_tenant ON knowledge_link (tenant_id);
CREATE INDEX idx_knowledge_link_source ON knowledge_link (source_id);
CREATE INDEX idx_knowledge_link_target ON knowledge_link (target_id);