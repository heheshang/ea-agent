-- agent_run 多维度统计落库（V3）：
--   usage       : 模型调用 token 明细（input/output/cached + 调用次数与毫秒耗时）
--   tool_calls  : 工具调用明细（名称/参数摘要/耗时/成败），含 skill 加载（load_skill_through_path）
--   cost        : 按模型单价估算的花费（美元）
--   prompt_info : 系统提示词版本/长度（审核提示词变更对成本的影响）+ 会话回顾注入条数
-- 旧行四列均为 NULL（统计 API 按 NULL 兼容）；tokens_used 由引擎完成时回写 = input+output
ALTER TABLE agent_run ADD COLUMN usage jsonb;
ALTER TABLE agent_run ADD COLUMN tool_calls jsonb;
ALTER TABLE agent_run ADD COLUMN cost numeric(12,6);
ALTER TABLE agent_run ADD COLUMN prompt_info jsonb;