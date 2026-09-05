-- campaign 人群快照（V10，修复「触达误发全量」）：活动创建/换人群时把圈定人群的成员
-- 固化为快照，发送只按快照（audience_snapshot），禁止发送时实时重算人群。
-- 内容：{"audience_id":1,"audience_name":"跑步爱好者","mode":"DYNAMIC","rule":"attributes.hobby == '跑步'",
--        "member_count":17,"customer_ids":[..],"snapshot_at":"2026-09-05T12:00:00Z"}
-- 列可空：存量活动（无快照）由发送管线惰性回填（AudienceSnapshotService.memberIds）。
ALTER TABLE campaign
  ADD COLUMN audience_snapshot jsonb;