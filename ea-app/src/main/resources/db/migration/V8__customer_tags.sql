-- customer 客户画像扩展（V8）：用户标签列（jsonb 数组，独立于 attributes 属性键值）。
-- 沿用 knowledge.tags 惯例（jsonb NOT NULL DEFAULT '[]'）；GIN 索引支持 tags @> '["VIP"]' 包含查询。
-- 管理端契约（PUT /api/objects/customer/{id}）：attributes 为整表替换、tags 为整表替换；
-- Agent 侧 updateCustomerState 的 attributes 维持深合并增量语义（运行期画像注入），两者用途不同。
ALTER TABLE customer
  ADD COLUMN tags jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_customer_tags ON customer USING gin (tags);