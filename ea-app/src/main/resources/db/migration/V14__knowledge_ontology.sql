-- 知识库本体化(V14):类型化记录 + 生命周期 + 取代链(等价表结构承载知识图,节点=条目,边=supersedes_id)。
-- 语义(与 MOOSEDev 论文对齐,符号层确定性规则,不引入 OWL/SPARQL 栈):
--   record_type  记录类别:decision(决策)/constraint(约束)/rule(规则)/lesson(经验教训)/rationale(理由)/fact(事实)/anti_pattern(反模式)
--   lifecycle    生命周期:active(现行)/superseded(已被取代)/obsolete(废弃)
--   supersedes_id 取代边:本条取代哪条(新→旧);设置取代时目标自动置 superseded;删除目标时引用 ON DELETE SET NULL 自动清空
-- 检索规则:对话注入与试检索默认只返回 active(被取代/废弃条目按构造排除——论文核心主张);
--          管理端可显式查 superseded/obsolete 与取代链。
ALTER TABLE knowledge
  ADD COLUMN record_type   varchar(32) NOT NULL DEFAULT 'rule',
  ADD COLUMN lifecycle     varchar(16) NOT NULL DEFAULT 'active',
  ADD COLUMN supersedes_id bigint REFERENCES knowledge(id) ON DELETE SET NULL;

-- 检索过滤(活性)+ 取代链遍历(反查 supersedes 我)
CREATE INDEX idx_knowledge_lifecycle ON knowledge (tenant_id, lifecycle, enabled);
CREATE INDEX idx_knowledge_supersedes ON knowledge (supersedes_id);