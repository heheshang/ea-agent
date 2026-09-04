-- Agent 权限下放（9.2）：run 落库发起用户角色，工具透传身份（历史行回填 USER，与旧行为一致）
ALTER TABLE agent_run ADD COLUMN role varchar(16) NOT NULL DEFAULT 'USER';