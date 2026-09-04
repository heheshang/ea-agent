-- =====================================================================
-- EA-Agent V1 初始结构（规范源：详细设计 v1.6 §8.1 物理 DDL + §8.2 索引）
-- PG 16 / Flyway
-- 说明：
--  1. delivery / event 不建表级分区：PG 分区表的全局唯一约束必须包含分区键，
--     (tenant_id, request_id) 幂等唯一约束与按月 RANGE 分区无法共存；
--     基线采用非分区 + 归档任务（event_archive 同构表），演进见交付说明。
--  2. tenant_user 补 password_hash 列（§9.1 bcrypt 认证所需，原 DDL 缺失）。
--  3. 租户隔离 = 复合 FK + 应用层 TenantContext + 显式 tenant_id，禁用租户插件。
-- =====================================================================

CREATE TABLE tenant (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name          varchar(128) NOT NULL,
  domain        varchar(255) UNIQUE NOT NULL,
  plan          varchar(16)  NOT NULL DEFAULT 'free',
  status        varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  quota         jsonb NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant_user (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  login_name    varchar(64) NOT NULL,
  name          varchar(64)  NOT NULL,
  password_hash varchar(100) NOT NULL,
  role          varchar(16)  NOT NULL,
  status        varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, login_name),
  UNIQUE (tenant_id, id)
);

CREATE TABLE customer (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  external_id   varchar(64),
  phone         varchar(32),
  email         varchar(128),
  wechat_openid varchar(64),
  attributes    jsonb NOT NULL DEFAULT '{}',
  status        varchar(16) NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, external_id),
  UNIQUE (tenant_id, id)
);

CREATE TABLE audience (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  name          varchar(128) NOT NULL,
  mode          varchar(8)  NOT NULL DEFAULT 'DYNAMIC',
  rule          text,
  owner_id      bigint NOT NULL,
  status        varchar(16) NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  CONSTRAINT fk_audience_owner FOREIGN KEY (tenant_id, owner_id) REFERENCES tenant_user(tenant_id, id),
  CONSTRAINT chk_audience_mode CHECK (
    (mode = 'DYNAMIC' AND rule IS NOT NULL AND rule <> '') OR
    (mode = 'STATIC'  AND (rule IS NULL OR rule = ''))
  )
);

CREATE TABLE audience_member (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  audience_id   bigint NOT NULL,
  customer_id   bigint NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, audience_id, customer_id),
  CONSTRAINT fk_am_audience FOREIGN KEY (tenant_id, audience_id) REFERENCES audience(tenant_id, id),
  CONSTRAINT fk_am_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customer(tenant_id, id)
);

CREATE TABLE template (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  channel       varchar(16) NOT NULL,
  title         varchar(256),
  content       text NOT NULL,
  vars          jsonb NOT NULL DEFAULT '[]',
  review_status varchar(16) NOT NULL DEFAULT 'DRAFT',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id)
);

CREATE TABLE campaign (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  name          varchar(128) NOT NULL,
  audience_id   bigint NOT NULL,
  channel       varchar(16) NOT NULL,
  template_id   bigint NOT NULL,
  schedule      timestamptz,
  cron          varchar(64),
  gray_ratio    int NOT NULL DEFAULT 100,
  ab_mode       varchar(8)  NOT NULL DEFAULT 'NONE',
  ab_split      smallint    NOT NULL DEFAULT 0,
  ab_variants   jsonb       NOT NULL DEFAULT '[]',
  owner_id      bigint NOT NULL,
  trigger_rule  jsonb,
  status        varchar(16) NOT NULL DEFAULT 'DRAFT',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_campaign_audience FOREIGN KEY (tenant_id, audience_id) REFERENCES audience(tenant_id, id),
  CONSTRAINT fk_campaign_template FOREIGN KEY (tenant_id, template_id) REFERENCES template(tenant_id, id),
  CONSTRAINT fk_campaign_owner FOREIGN KEY (tenant_id, owner_id) REFERENCES tenant_user(tenant_id, id),
  CONSTRAINT chk_campaign_gray CHECK (gray_ratio BETWEEN 0 AND 100),
  CONSTRAINT chk_campaign_ab CHECK (
    ab_mode = 'NONE' OR (ab_split BETWEEN 1 AND 99 AND jsonb_typeof(ab_variants) = 'array'
                         AND jsonb_array_length(ab_variants) BETWEEN 1 AND 3)
  ),
  UNIQUE (tenant_id, id)
);

CREATE TABLE channel_config (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  channel       varchar(16) NOT NULL,
  config_encrypted text NOT NULL,
  enabled       boolean NOT NULL DEFAULT true,
  frequency_limit jsonb NOT NULL DEFAULT '{}',
  callback_secret text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, channel)
);

CREATE TABLE delivery (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id    varchar(64) NOT NULL,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  campaign_id   bigint,
  customer_id   bigint NOT NULL,
  channel       varchar(16) NOT NULL,
  template_id   bigint,
  channel_msg_id varchar(128),
  gray_hit      boolean NOT NULL DEFAULT false,
  ab_group      varchar(16),
  status        varchar(16) NOT NULL DEFAULT 'PENDING',
  error         text,
  attempt       int NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, request_id),
  UNIQUE (tenant_id, channel_msg_id),
  CONSTRAINT fk_delivery_campaign FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaign(tenant_id, id),
  CONSTRAINT fk_delivery_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customer(tenant_id, id),
  CONSTRAINT fk_delivery_template FOREIGN KEY (tenant_id, template_id) REFERENCES template(tenant_id, id)
);

CREATE TABLE event (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  customer_id   bigint,
  event_type    varchar(64) NOT NULL,
  payload       jsonb NOT NULL DEFAULT '{}',
  dedup_key     varchar(128) NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, dedup_key),
  CONSTRAINT fk_event_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customer(tenant_id, id)
);

CREATE TABLE event_archive (
  id            bigint PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  customer_id   bigint,
  event_type    varchar(64) NOT NULL,
  payload       jsonb NOT NULL DEFAULT '{}',
  dedup_key     varchar(128) NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  archived_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE unsubscribe (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  customer_key  varchar(128) NOT NULL,
  channel       varchar(16) NOT NULL,
  reason        varchar(256),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (customer_key, channel)
);

CREATE TABLE agent_run (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  session_id    varchar(64) NOT NULL,
  user_id       bigint NOT NULL,
  goal          text NOT NULL,
  plan          jsonb,
  decisions     jsonb,
  status        varchar(16) NOT NULL DEFAULT 'NEW',
  tokens_used   bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_agent_run_user FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user(tenant_id, id)
);

CREATE TABLE action_log (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id    varchar(64) NOT NULL,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  actor_type    varchar(8)  NOT NULL,
  actor_id      varchar(64) NOT NULL,
  action        varchar(64) NOT NULL,
  args          jsonb,
  result        jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- §8.2 索引
CREATE INDEX idx_customer_tenant        ON customer (tenant_id, status, updated_at);
CREATE INDEX idx_customer_attr          ON customer USING GIN (attributes);
CREATE INDEX idx_campaign_tenant_status ON campaign (tenant_id, status, schedule);
CREATE INDEX idx_campaign_tenant_aud    ON campaign (tenant_id, audience_id);
CREATE INDEX idx_delivery_tenant_camp   ON delivery (tenant_id, campaign_id, created_at DESC);
CREATE INDEX idx_delivery_status        ON delivery (tenant_id, status, created_at DESC);
CREATE INDEX idx_event_tenant_type      ON event (tenant_id, event_type, created_at DESC);
CREATE INDEX idx_event_tenant_customer  ON event (tenant_id, customer_id, created_at DESC);
CREATE INDEX idx_agent_run_tenant       ON agent_run (tenant_id, session_id, created_at DESC);
CREATE INDEX idx_action_log_tenant      ON action_log (tenant_id, created_at DESC);
CREATE INDEX idx_audience_member        ON audience_member (tenant_id, customer_id);