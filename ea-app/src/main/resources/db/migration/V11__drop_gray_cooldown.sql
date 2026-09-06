-- =====================================================================
-- EA-Agent V11 移除灰度推送与冷却期机制
-- 2026-09-06
-- 灰度推送（campaign.gray_ratio + chk_campaign_gray）与冷却期
-- （trigger_rule.cooldown、delivery.gray_hit 审计）整机制移除：
-- 发送管线不再抽样/去重，字段与 CHECK 一并删除。
-- 存量 trigger_rule jsonb 中的 cooldown 键清理（window 保留）。
-- 演示知识库同步：seed 中「冷却窗与频控」「灰度发布」两条目内容
-- 已随机制移除（仅清种子原文，命中即未被编辑的演示条目）。
-- =====================================================================

ALTER TABLE campaign DROP CONSTRAINT IF EXISTS chk_campaign_gray;
ALTER TABLE campaign DROP COLUMN IF EXISTS gray_ratio;
ALTER TABLE delivery DROP COLUMN IF EXISTS gray_hit;

-- trigger_rule jsonb：删除存量 cooldown 键（window/event_type 保留）
UPDATE campaign SET trigger_rule = trigger_rule - 'cooldown'
WHERE trigger_rule IS NOT NULL AND trigger_rule ? 'cooldown';

-- 演示知识库种子条目随机制移除（种子原文本；用户编辑过标题的不命中）
DELETE FROM knowledge
WHERE (title = '冷却窗与频控' AND content LIKE '同一客户在活动触发规则的冷却窗口%')
   OR (title = '灰度发布' AND content LIKE '活动支持灰度发布%');