# EA-Agent 技术栈设计（技术选型与工程基线）

> 版本：v0.2 · 类型：技术栈设计文档 · 状态：设计草案
> 对应：总体架构 v1.4（9.1 技术选型 / 9.2 代码模块划分）· 详细设计 v1.6（1.3 全局设计约定 / 3.5 EA-Bus / 10 关键流程）· 数据流 v1.6

## 1. 定位与范围

本文档把架构 9.1（技术选型）与 9.2（模块划分）、详细设计 1.3（包结构）落实为可执行的工程基线：**版本选型、依赖清单、装配方式、配置骨架、关键集成机制、前端工程结构与联调启动**。

不重复对象模型 / Action 框架 / 安全 / 数据 DDL 设计（见详细设计 3/4/5/8/9），仅给技术落点与集成要点。命名、编号、禁用以详细设计 1.3 与数据流 7 为准。

## 2. 版本选型

| 层 | 组件 | 版本 | 用途 | 依据 |
|---|---|---|---|---|
| 运行时 | JDK | 17（LTS） | 服务基线 | agentscope-harness 要求 JDK 17+（架构 9.1） |
| 后端框架 | Spring Boot | 3.4.x | 服务编排、Web、异步、SSE | 与 JDK 17 / MyBatis-Plus 3.5 兼容；可平滑升 3.5 |
| 数据访问 | MyBatis-Plus | 3.5.9+（`mybatis-plus-spring-boot3-starter`） | ORM、分页、JSON 映射 | 详细设计 8（DDL 对齐） |
| 数据库 | PostgreSQL | 16 + pgjdbc | 业务库；jsonb / 复合 FK / CHECK / 分区 | DDL 8.1（CHECK 依赖 jsonb 函数） |
| 缓存/队列 | Redis | 7.x + Lettuce（Spring Data Redis） | 幂等（ea:idem:*）、频控（ea:fc:*）、熔断（ea:cb:*）、EA-Bus 事件流（ea:events Streams）、MFA 挑战 | 数据流 7.1/7.2 |
| AI 框架 | agentscope-java-2.0 | `io.agentscope:agentscope-harness` 2.0.x | ReActAgent/HarnessAgent、工具权限、31 类 Agent 会话事件、分布式会话记忆 | 架构 9.1 [6] |
| 编译期 | Lombok | 1.18.3x | 样板代码 | 既有技术栈行 |
| 前端框架 | Vue | 3.4+ | 组合式 SFC | 7.3 组件树 |
| 前端语言 | TypeScript | 5.4+（strict） | 类型安全（DTO 与后端契约） | 7.3 |
| 构建 | Vite | 5.x | dev server / 按需构建 | — |
| UI | Element Plus | 2.7+ + `@element-plus/icons-vue` | 组件库、画布 | 7.3 |
| 状态 | Pinia | 2.1+ | 租户上下文 / 会话 / Agent 聊天流 | — |
| HTTP | Axios | 1.7+ | REST 调用、拦截器 | 7.1 API 清单 |
| 测试 | JUnit 5 + Mockito + Testcontainers | 5.10+ / 5.9+ / 1.19+ | 单元；`postgres:16` `redis:7` 集成 | — |

版本集中在 parent POM `<properties>` 与前端 `package.json` 单处声明，升级只改版本属性（统一依赖管理，杜绝传递依赖漂移）。

## 3. 工程基线（Maven 多模块）

模块划分与依赖方向**严格复用**架构 9.2 / 详细设计 1.3（Maven 模块 = 顶层包 `com.eaagent`），不另立约定：

```
ea-app        启动装配、网关过滤器（X-Tenant-Id 解析 → TenantContext）、全局异常
ea-api        REST 控制器、DTO、SSE 协议类（EventPayload/EventType 31 类）
ea-ontology   对象模型（TypeRegistry/ObjectTypeDef/LinkDef）、ActionRegistry + applyAction 管线、
              对象 API 实现、EA-Bus 消费（Streams）、调度器
ea-agent      agentscope 装配：AgentRunner、@Tool 注册（applyAction）、会话状态机、审批门控、记忆
ea-channel    通道适配器（email/sms/wechat/push）、回执回调、ConsoleChannelAdapter 降级
ea-common     租户上下文（TenantContext ThreadLocal）、错误码、Result、幂等器、
              加密（CryptoService）、脱敏工具（无业务依赖）
```

依赖方向：`common ← api ← ontology ← agent`；`channel` 依赖 `common + ontology`；`app` 依赖全部。禁止反向依赖。

前端 `ea-web/`（独立目录，Vite 工程）：

```
ea-web/src
├── api/        axios.ts（拦截器）、sse.ts（fetch+ReadableStream）、*.ts 业务 API
├── components/ Element Plus 业务组件（CampaignCanvas、AudienceRuleEditor…）
├── stores/     tenant.ts / session.ts / agentChat.ts（Pinia）
├── router/     （守卫：登录态 + MFA 挑战流）
├── views/      （对象管理页、Agent 对话页）
└── types/      DTO 类型（与后端契约对应）
```

## 4. 后端技术要点

**4.1 Spring Boot 装配**

- 统一返回 `Result<T> {code, message, request_id}`（code=0 成功）；`@RestControllerAdvice` 全局异常 → 附录 B 错误码映射（如校验失败 E-10001、无权限 E-10003、MFA E-10004）。
- 写操作请求头约定：`X-Tenant-Id`（网关解析注入，缺失 E-11001）、`X-Request-Id`（幂等键）+ 敏感端点 `X-MFA-Token`（9.1）。
- 参数校验 `spring-boot-starter-validation`（jakarta validation）；Bean 校验失败统一转 E-10001。
- 异步：`@EnableAsync` 自定义 `TaskExecutor`（Action 审计、回调处理），与 SSE 线程池分离（4.6）。

**4.2 MyBatis-Plus 与多租户**

- **不用 MyBatis-Plus 租户插件**（TenantLine 拦截器重写 SQL 注入租户条件）——租户隔离采用既有纵深：应用层 `TenantContext` 注入 + 写路径显式 `tenant_id` + DDL 复合 FK `REFERENCES x(tenant_id, id)` 拦截跨租户（详细设计 8.4）。理由：人群 DYNAMIC 成员等 RuleEngine 动态派生 SQL 无法插件化注入租户条件；复合 FK 已在存储层兜底（ADR-1 / 数据流 §6）。
- `JacksonTypeHandler` 映射 jsonb 列：`customer.attributes`、`campaign.trigger_rule`、`channel_config.frequency_limit` 等。
- `PaginationInnerInterceptor` 分页；主键 `IdType.ASSIGN_ID`（雪花，分区表友好）。
- boolean/枚举列保持 varchar/smallint 显式类型，不做字段级魔法。

**4.3 Redis（Lettuce / StringRedisTemplate）**

- 字符串协议为主（SETNX / INCR / EXPIRE / Streams），不引入额外反序列化依赖。
- key 命名空间总表（对齐数据流 7.1，全部带 TTL）：

| key | 容量 | TTL | 用途 |
|---|---|---|---|
| `ea:idem:{tenant}:{requestId}` | SETNX | 24h | 写操作幂等 |
| `ea:fc:{tenant}:{channel}:{customerId}:{date}` | INCR | 当日 | 频控计数 |
| `ea:cb:{channel}` | SET | 60s | 通道熔断 |
| `ea:mfa:{userId}` | STRING | 挑战 TTL | MFA 挑战（9.1） |
| `ea:events` / `ea:events:dlq` | Stream | 无常驻 | EA-Bus 事件队列（3.5） |
| `AgentSession:{sessionId}` | STRING | 24h | Agent 会话（7.2） |
| `ea:agent:mode:{tenant}:{session}` | STRING | 1d | 会话模式 auto/suggest（web chat 写，缺省 auto） |
| `ea:agent:approval:pending` | List | 决策后保留 | 建议模式挂起写动作（JSON entry） |

- EA-Bus 消费：`StreamMessageListenerContainer` 手动 ACK（XACK），处理失败 `XADD ea:events:dlq` + 告警；消费组 `ea:consumer`（数据流 3.3）。

**4.4 PostgreSQL**

- 复合 FK 纵深 + CHECK（`chk_audience_mode`）按 8.1 DDL 建表；schema 脚本来源于 8.1，可接 Flyway 版本化（基线不强制）。**Flyway 已启用**：`V13__campaign_workflow.sql` 提供 `campaign.workflow jsonb` + `delivery.workflow_node varchar(64)`（启动自动迁移）。
- `delivery` 按月 RANGE 分区预建（调度任务每月 25 日预建下月分区，8.3）；`event` 默认 90 天归档到 `event_archive`（8.3）。

**4.5 agentscope-java-2.0**

- 初始化：`AgentScope.init`（模型配置：OpenAI 兼容网关，环境变量 `MODEL_API_KEY / MODEL_BASE_URL / MODEL_NAME`，默认 DashScope 兼容端点；模型接入点抽象在 `ea-agent`，可切 Ollama 本地验证）。
- 工具注册：`@Tool` 注册到 AgentRunner——**真实执行工具仅 `applyAction`**（ActionRegistry 委托，3.3/ADR-3）；决策咨询函数经 `FunctionRegistry` 注册、`callFunction` 单工具路由（audienceStats / frequencyCheck / channelPreference / churnRiskScore / bestSendTime，只读咨询，与 ActionRegistry 对称）。
- 权限模式：建议模式 = require approval（高危动作转人工，4.4）；自动模式 = allow（限定工具范围）。**会话门控（代码 V13）**：`AgentToolRegistry` 增 `forTenant(tenantId,userId,role,sessionId)`，suggest 且写动作 → `applyAction` 挂起 Redis List `ea:agent:approval:pending`（LinkHashMap 稳定键序 JSON）返回 PENDING_APPROVAL；`ApprovalService.decide` 批准以原请求身份执行，LREM 精确串匹配原位更新；权限 `Roles.ROLE_LEVEL ≥ REVIEWER`，越权 E-10003。
- 写动作工具集（V13）：`createTemplate`（模板直建，产物 APPROVED）→ `createCampaign`（requiredArgs=name/audience_id；顶层 channel/template_id 缺省取 workflow 首节点；workflow 经 `WorkflowCodec.validate`）；`WorkflowExecutor` + `WorkflowConditionEvaluator`（event/customer/prev 三组条件）驱动 `campaign.workflow` 逐客户执行，`EventConsumer` 判 workflow 非空分流。
- 会话事件：31 类 Agent 会话事件（thinking/tool_call/approval_required/text_delta/done…）经 `SseEmitter` 流式回传（3.5/7.4）。
- 会话与记忆：`AgentSession` 存 Redis（7.2）；`agent_run` 落库（plan/decisions/tokens_used，10.2）。

**4.6 SSE 与异步**

- 两段式契约：`POST /api/agent/chat` 发起（建 agent_run + AgentSession，返回 run_id）→ `GET /api/agent/chat?request_id=…` SSE 订阅（7.4）。
- 实现：Spring MVC `SseEmitter` + 专用 `TaskExecutor`（核心线程数独立可配，防阻塞 Tomcat 工作线程）；服务端周期性注释心跳；超时/断线由前端重连（5.3）。

**4.7 加密与审计**

- 信封加密（9.3）：`CryptoService`（ea-common）——数据密钥 AES-256-GCM 加密字段密文，数据密钥由主密钥（KMS / 环境变量 `MASTER_KEY`）包装；`channel_config.config_encrypted / callback_secret` 与属性脱敏共用。
- 审计：`action_log` 全量动作落库（3.4 第 6 步）——`@TransactionalEventListener(AFTER_COMMIT)` 异步写入，不阻塞主事务；`agent_run.decisions` 决策回放随会话落库。

**4.8 幂等**

- `IdempotencyService`（ea-common）：SETNX `ea:idem:{tenant}:{requestId}` 先滤 + 落库唯一约束兜底（`delivery(tenant_id, request_id)`、`event(tenant_id, dedup_key)`、回调 `(tenant_id, channel_msg_id)`），重复请求返回首次结果（数据流 2.2/3.4/3.5）。

## 5. 前端技术要点

**5.1 工程与组件**

- Vue3 `<script setup>` 组合式 SFC；TS `strict`；Element Plus 按需引入（`unplugin-vue-components` + `ElementPlusResolver`）；图标 `@element-plus/icons-vue`。
- 关键业务组件对应 7.3 组件树：CampaignCanvas（人群×渠道×模板×时间×触发规则画布）、AudienceRuleEditor、CustomerDetail、AgentChatView。

**5.2 网络层**

- Axios 实例统一拦截：
  - 请求：注入 `X-Tenant-Id`（Pinia tenant store）、`X-Request-Id`（写操作 `crypto.randomUUID()`，失败重试复用同一 id → 服务端幂等）、敏感端点 `X-MFA-Token`。
  - 响应：解包 `Result`，`code !== 0` 统一 `ElMessage.error`（错误码文案映射表对应附录 B）；401/E-10002/E-10004 触发登录/MFA 流程。
- 写操作重试策略：仅对网络层失败重试（服务端幂等保证不重复生效），业务失败不盲重。

**5.3 SSE 事件流**

- `EventSource` 不支持自定义请求头（X-Tenant-Id / Authorization）→ 用 `fetch` + `ReadableStream` + `TextDecoder` 按 `data:` 行解析 `text/event-stream`（封装 `sse.ts`）。
- 事件分派：`thinking / tool_call / approval_required / text_delta / done` → Pinia `agentChat` store 增量渲染；`approval_required` 挂起等待人工决策（4.4 审批）；断线自动重连，`done(agent_run_id)` 后落智能卡片。

**5.4 状态与路由**

- Pinia stores：`tenant`（X-Tenant-Id 来源，登录时从 JWT claims 取得）、`session`（登录态 + MFA 挑战流 9.1）、`agentChat`（会话事件流）。
- 路由守卫：未登录 → 登录页；敏感端点触发 MFA 挑战 → 挑战页 → 回跳原路由。

## 6. 配置骨架

**6.1 application.yml 关键段**（ea-app）

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/eaagent}
    username: ${DB_USER:eaagent}
    password: ${DB_PASSWORD:eaagent}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
      timeout: 2s
agentscope:
  model:
    base-url: ${MODEL_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
    api-key: ${MODEL_API_KEY:}
    name: ${MODEL_NAME:qwen-plus}
ea:
  sse:
    executor-cores: 8
    emitter-timeout-ms: 30000
    heartbeat-ms: 15000
  partition:
    precreate-day: 25      # delivery 分区预建日（8.3）
  security:
    master-key: ${MASTER_KEY:}   # 信封加密主密钥（9.3）
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: assign_id
```

**6.2 vite.config.ts 关键段**（ea-web）

```ts
server: {
  proxy: {
    '/api':   { target: 'http://localhost:8080', changeOrigin: true },
    '/agent': { target: 'http://localhost:8080', changeOrigin: true },
  },
},
plugins: [vue(), AutoImport({ resolvers: [ElementPlusResolver()] }),
          Components({ resolvers: [ElementPlusResolver()] })]
```

**6.3 docker-compose（本地基线）**

```yaml
services:
  postgres:
    image: postgres:16
    environment: { POSTGRES_DB: eaagent, POSTGRES_USER: eaagent, POSTGRES_PASSWORD: eaagent }
    ports: ["5432:5432"]
  redis:
    image: redis:7
    ports: ["6379:6379"]
```

## 7. 联调与启动

1. `docker compose up -d`（PG16 + Redis7）→ 执行 8.1 建表脚本（或 Flyway migrate）。
2. 启动 `ea-app`（`mvn -pl ea-app spring-boot:run`，默认 8080）；本地无真实通道时未配置通道走 ConsoleChannelAdapter 降级（6.2）。
3. `cd ea-web && pnpm dev`（默认 5173，/api /agent 代理到 8080）。
4. 联调顺序建议：租户登录（JWT + X-Tenant-Id）→ 对象 CRUD（幂等头验证）→ 模板/通道配置 → 人群 DSL → 任务编排 → 事件导入触发 → Agent 对话 SSE 全链路。

## 8. 与既有设计的一致性

| 定名 / 约定 | 技术落点 | 源头 |
|---|---|---|
| EA-Bus 事件流 | Redis Streams `ea:events` + 消费组 + DLQ | 详细设计 3.5 / 数据流 3.3 |
| Agent 会话事件（31 类） | agentscope 事件 → SseEmitter → 前端 agentChat store | 3.5 / 7.4 |
| `applyAction` 单一工具 | ea-agent @Tool → ActionRegistry 委托 | 3.3 / ADR-3 |
| TypeRegistry / dynamic security | ea-ontology 对象模型 + 归属校验（E-12005） | 3.1 / 5.4 |
| `(tenant_id, request_id)` 幂等 | IdempotencyService + 唯一约束 | 数据流 2.2 |
| 多租户 | 不用租户插件；TenantContext + 复合 FK | ADR-1 / 8.4 |
| 明文边界 | 信封加密密文落库，明文仅调用栈 | 9.3 / 6.2 |