-- 人工模板批注与最终审核：版本绑定原文，审核记录不保存聊天正文。
ALTER TABLE template ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE template ADD CONSTRAINT chk_template_version CHECK (version >= 0);

CREATE TABLE template_review (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id        bigint NOT NULL REFERENCES tenant(id),
  template_id      bigint NOT NULL,
  template_version bigint NOT NULL,
  chat_id          bigint,
  user_id          bigint NOT NULL,
  decision         varchar(16) NOT NULL,
  comment          varchar(2000) NOT NULL,
  title            varchar(256),
  content          text NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_template_review_template FOREIGN KEY (tenant_id, template_id)
    REFERENCES template(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_template_review_user FOREIGN KEY (tenant_id, user_id)
    REFERENCES tenant_user(tenant_id, id),
  CONSTRAINT fk_template_review_chat FOREIGN KEY (tenant_id, chat_id)
    REFERENCES agent_chat(tenant_id, id) ON DELETE SET NULL (chat_id),
  CONSTRAINT chk_template_review_version CHECK (template_version >= 0),
  CONSTRAINT chk_template_review_decision CHECK (decision IN ('COMMENT', 'APPROVE', 'REJECT')),
  CONSTRAINT chk_template_review_comment CHECK (decision = 'APPROVE' OR btrim(comment) <> '')
);

CREATE INDEX idx_template_review_history ON template_review (tenant_id, template_id, created_at, id);
-- 同一模板版本至多一次最终决定；普通批注可追加多条。
CREATE UNIQUE INDEX uq_template_review_final ON template_review (tenant_id, template_id, template_version)
  WHERE decision IN ('APPROVE', 'REJECT');
