-- =====================================================================
-- EA-Agent V12 移除 AB 实验机制
-- 2026-09-06
-- AB 实验（campaign.ab_mode/ab_split/ab_variants + chk_campaign_ab、
-- delivery.ab_group 审计）整机制移除：发送管线不再分桶，列与 CHECK
-- 一并删除。演示知识库同步：seed 中「AB 实验」条目内容已随机制移除
-- （仅清种子原文，命中即未被编辑的演示条目）。
-- =====================================================================

ALTER TABLE campaign DROP CONSTRAINT IF EXISTS chk_campaign_ab;
ALTER TABLE campaign DROP COLUMN IF EXISTS ab_mode;
ALTER TABLE campaign DROP COLUMN IF EXISTS ab_split;
ALTER TABLE campaign DROP COLUMN IF EXISTS ab_variants;
ALTER TABLE delivery DROP COLUMN IF EXISTS ab_group;

-- 演示知识库种子条目随机制移除（种子原文本；用户编辑过标题的不命中）
DELETE FROM knowledge
WHERE title = 'AB 实验' AND content LIKE '活动支持 AB 实验%';