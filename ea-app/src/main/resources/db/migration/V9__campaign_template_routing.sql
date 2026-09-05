-- campaign 模板路由（V9）：触发规则 → 模板 映射 jsonb。
-- 规则条目：{"event_type":"order_placed","conditions":[{"attr":"new_customer","op":"eq","value":true}],"template_id":2}
-- event_type 可空（不约束事件）；conditions AND；顺序匹配、首条命中；未命中回退 campaign.template_id。
-- 列可空：null = 无规则路由（仅主模板）。
ALTER TABLE campaign
  ADD COLUMN template_routing jsonb;