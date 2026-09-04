# 基于 Ontology 底座的 AI-Agent 智能运营系统 · 详细设计文档

> **EA-Agent · 多通道运营触达智能体（SaaS 多租户）**
> 版本：v1.6 · 类型：详细设计文档 · 状态：设计定稿
> 技术栈：Vue3 + TypeScript + Element Plus ｜ Spring Boot + MyBatis-Plus + PostgreSQL + Redis + agentscope-java-2.0 + Lombok

---

## 1. 引言

### 1.1 目的与范围

本文档是《基于 Ontology 底座的 AI-Agent 智能运营系统 · 总体架构文档》（v1.4）的实现级细化：模块结构、类与接口、对象 API 契约、Action 框架、业务事件总线与 Agent 会话事件、Agent 状态机、租户机制、通道层、数据 DDL、安全实现、关键流程时序与配置。目标读者：参与实施的工程师。

### 1.2 文档体系与阅读约定

| 文档 | 版本 | 定位 |
|---|---|---|
| ea-agent-ontology-ai-design.md | v0.1 设计草案 | 概念、术语、设计方向 |
| ea-agent-architecture.md | v1.4 总体架构 | 分层、组件、决策（ADR-1~8）、NFR |
| ea-agent-detailed-design.md（本文档） | v1.6 详细设计 | 类/接口/契约/流程，可直接编码 |
| ea-agent-tech-stack.md | v0.1 技术栈设计 | 版本选型、工程基线、依赖/配置/联调（架构 9.1/9.2 细化） |

约定：术语、命名、编号与架构文档一致；引用 [1]–[7] 沿用同一体系；未在本文档细化处，以架构文档为准。

### 1.3 全局设计约定

**包结构**（Maven 模块 = 顶层包）：

```
com.eaagent
├── common     (ea-common)   租户上下文、错误码、幂等器、Result
├── api        (ea-api)      DTO、REST 接口、SSE 协议类
├── ontology   (ea-ontology) 对象模型、Action 框架、事件、调度
├── agent      (ea-agent)    agentscope 装配、工具、状态机、记忆
├── channel    (ea-channel)  通道适配器、回执回调
└── app        (ea-app)      启动装配、网关过滤器、全局异常
```

**依赖方向**：`common ← api ← ontology ← agent`；`channel` 依赖 `common + ontology`；`app` 依赖全部。禁止反向依赖。

**错误码体系**（响应统一 `{code, message, request_id}`，code=0 成功）：

| 段位 | 含义 |
|---|---|
| 10xxx | 通用（10001 参数错误 / 10002 未认证 / 10003 无权限 / 10004 MFA 校验失败） |
| 11xxx | 租户（11001 上下文缺失 / 11002 不匹配 / 11003 停用） |
| 12xxx | 对象（12001 不存在 / 12002 归属校验失败 / 12003 DSL 解析失败 / 12004 类型未知 / 12005 动态安全越权 / 12006 人群模式写成员拒绝） |
| 13xxx | Action（13001 未注册 / 13002 校验失败 / 13003 幂等冲突 / 13004 频控 / 13005 退订 / 13006 配额 / 13007 时段） |
| 14xxx | 通道（14001 未配置 / 14002 不可用 / 14003 发送失败 / 14004 回执验签失败） |
| 15xxx | Agent（15001 会话不存在 / 15002 状态不允许 / 15003 待审批） |
| 16xxx | AI（16001 LLM 调用失败 / 16002 输出校验失败） |

**幂等约定**：所有写操作（Action、事件导入、审批、回调）必须携带 `request_id`；服务端以 `(tenant_id, request_id)` 幂等键去重，重复请求返回首次结果。

**租户约定**：网关解析并注入 `X-Tenant-Id`；服务层经 `TenantContext` 读取；缺失即拒绝（E-11001）。LLM 侧永不可见 `X-Tenant-Id`。

---

## 2. 工程结构与模块设计

### 2.1 模块依赖与职责

| 模块 | 关键包 | 对外提供 | 依赖 |
|---|---|---|---|
| ea-common | context / error / idem / util | TenantContext、BizException、IdempotencyService、Result | — |
| ea-api | dto / rest / sse | API 接口、DTO、SSE 事件模型 | common |
| ea-ontology | object / action / event / schedule / rule | ObjectApiService、ActionRegistry、EventService、ScheduleService、RuleEngine | common, api |
| ea-agent | agent / tool / memory / state / approval | AgentService、ToolRegistry、AgentStateMachine、MemoryService、ApprovalService | common, api, ontology |
| ea-channel | adapter / callback / encrypt | ChannelAdapterRegistry、ChannelCallbackController | common, ontology |
| ea-app | config / filter / bootstrap | 启动类、TenantFilter、全局异常处理 | 全部 |

### 2.2 公共设施（ea-common）

**TenantContext**：ThreadLocal 持有当前租户与用户（见 5 章）。

**Result / BizException**：

```java
public record Result<T>(int code, String message, String requestId, T data) {
    public static <T> Result<T> ok(T data);
    public static <T> Result<T> fail(int code, String message);
}
public class BizException extends RuntimeException {          // 携带错误码
    private final int code;
    public BizException(int code, String message);
}
```

**IdempotencyService**：Redis `SETNX ea:idem:{tenant}:{requestId}` + 结果缓存（TTL 24h）；命中返回首次结果。

**分布式锁**：`RedisLock.tryLock(key, ttl)` —— 调度、幂等并发、审批并发共用。

---

## 3. Ontology 底座详细设计

### 3.1 对象模型与服务

**对象分类**（F，对应架构 4.1）：**业务实体** = Customer（Ontology 本义，LLM 感知世界的主要对象）；**行为对象** = Campaign / Delivery（操作的实例与痕迹）；**配置对象** = Template / Channel；**派生集合** = Audience。Agent 推理以业务实体网络为中心，行为/配置对象仅作操作上下文。

**对象类型注册表**（TypeRegistry，schema 驱动）：封闭枚举会阻塞新增对象类型且不体现接口多态（C）——改为注册表 + 元数据；DTO/Entity 仍为硬编码类，但类型定义、校验、接口能力全部从注册表读取，新增类型只加 Def 不改枚举与 Action 代码：

```java
public record ObjectTypeDef(String name, Class<?> entityCls, Class<?> dtoCls,
                            List<String> interfaces, List<FieldDef> fields) {}

public final class TypeRegistry {
    public static final String IFACE_TOUCHABLE = "touchable";   // 具备 phone / email / wechat_openid 之一
    private static final Map<String, ObjectTypeDef> DEFS = Map.ofEntries(
        entry("customer",  new ObjectTypeDef("customer",  CustomerEntity.class,  CustomerDto.class,  List.of(IFACE_TOUCHABLE), CUSTOMER_FIELDS)),
        entry("audience",  new ObjectTypeDef("audience",  AudienceEntity.class,  AudienceDto.class,  List.of(), AUDIENCE_FIELDS)),
        entry("campaign",  new ObjectTypeDef("campaign",  CampaignEntity.class,  CampaignDto.class,  List.of(), CAMPAIGN_FIELDS)),
        entry("template",  new ObjectTypeDef("template",  TemplateEntity.class,  TemplateDto.class,  List.of(), TEMPLATE_FIELDS)),
        entry("channel",   new ObjectTypeDef("channel",   ChannelConfigEntity.class, ChannelConfigDto.class, List.of(), CHANNEL_FIELDS)),
        entry("delivery",  new ObjectTypeDef("delivery",  DeliveryEntity.class,  DeliveryDto.class,  List.of(), DELIVERY_FIELDS)),
        entry("event",     new ObjectTypeDef("event",     EventEntity.class,     EventDto.class,     List.of(), EVENT_FIELDS))
    );
    public static ObjectTypeDef def(String type) {            // 未知类型抛 E-12004
        ObjectTypeDef d = DEFS.get(type); Objects.requireNonNull(d, "unknown type: " + type); return d;
    }
    public static boolean implementsInterface(String type, String iface) {
        return def(type).interfaces().contains(iface);
    }
}
```

**Interfaces 落地**（C）：`touchable` 接口 = 具备 phone / email / wechat_openid 之一的类型；`sendTouch` 前置校验 `TypeRegistry.implementsInterface(targetType, IFACE_TOUCHABLE)`，面向接口而非具体类型 —— v1 内置 customer，会员/线索经注册表扩展（新增 Def + 元数据，不改枚举与 Action 代码，与架构 4.1 一致）。

**ObjectApiService**（统一对象查询，唯一数据入口）：

```java
public interface ObjectApiService {
    PageResult<Map<String, Object>> search(String type, Rule filter, String sort, String pageToken, int limit);
    Map<String, Object> get(String type, String id);                 // 含归属校验(5.4)
    PageResult<Map<String, Object>> links(String type, String id, String linkType, String pageToken, int limit);
    Map<String, Object> stats(String type, Rule filter);             // count/聚合
}
```

实现要点：`search` 把业务语义 Rule 交给 RuleEngine 翻译为带租户条件的安全查询；返回字段按对象 Schema 白名单投影；Agent 渠道（tools 调用）额外执行「LLM 脱敏视图」（9.6）。

### 3.1.1 字段白名单（FieldDef 与对象 Schema）

`ObjectTypeDef.fields` 的类型定义与白名单（上文 3.1 引用的 `CUSTOMER_FIELDS … EVENT_FIELDS` 七组常量在此定案，供 schema 白名单投影、DSL 字段校验、脱敏视图三处复用）：

```java
public record FieldDef(String name, FieldType type, boolean queryable, boolean sensitive) {}
// FieldType: STRING | NUMBER | BOOLEAN | DATETIME | ENUM(注释列允许值) | JSON
```

| 常量 | 对象 | 白名单可查询字段（queryable=true） | sensitive 字段（工具返回脱敏） |
|---|---|---|---|
| CUSTOMER_FIELDS | customer | id, status, external_id, created_at, updated_at, attributes.* | phone / email / wechat_openid（`attributes` 动态路径运行时检测掩码） |
| AUDIENCE_FIELDS | audience | id, name, mode, rule, owner_id, created_at | —（rule 为业务配置，非个人数据；成员明细走 `links()`） |
| CAMPAIGN_FIELDS | campaign | id, name, status, schedule, gray_ratio, ab_mode, ab_split, ab_variants, trigger_rule, audience_id, template_id, owner_id, created_at | — |
| TEMPLATE_FIELDS | template | id, name, channel, review_status, created_at | content（含 var 引用，渲染脱敏预览） |
| CHANNEL_FIELDS | channel | id, channel, enabled, frequency_limit, created_at | config_encrypted / callback_secret **永不投影**（无对应 FieldDef） |
| DELIVERY_FIELDS | delivery | id, campaign_id, customer_id, channel, status, gray_hit, request_id, created_at | — |
| EVENT_FIELDS | event | id, customer_id, event_type, payload, dedup_key, created_at | payload 内 phone/email 键运行时掩码 |

规则：
- **投影**：对象 API / DSL 校验只认白名单字段；未注册字段 → E-12003（DSL 字段非法）。
- **动态属性**：`attributes.*` 为开放 jsonb 路径，白名单校验 = 顶层键必须存在且为已知动态键（`customer.attributes` jsonb 索引 8.2），值类型按 FieldDef.type 运行时校验；敏感键（phone/email/wechat_openid）投影时掩码。
- **敏感列**：`sensitive=true` 字段在工具返回与 API 投影时掩码（`138****1234`），明文仅存在于 Action 执行的服务端调用栈（9.6）；`CHANNEL_FIELDS` 压根不定义凭据字段——密文列无 FieldDef，物理层杜绝投影。

### 3.2 统一对象 API 契约

**端点**：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/objects/{type}` | 列表查询（cursor 分页） |
| POST | `/api/objects/{type}/search` | POST 体查询（复杂 filter） |
| GET | `/api/objects/{type}/{id}` | 单对象 |
| GET | `/api/objects/{type}/{id}/links/{link}` | 链接出边查询 |
| POST | `/api/objects/{type}/{id}/links/{link}/{targetId}` | 建立链接（写，走 Action 校验） |
| DELETE | `/api/objects/{type}/{id}/links/{link}/{targetId}` | 断链（写） |

**查询请求**：

```json
{
  "filter": "status == 'active' AND last_order_at < '2026-08-01' AND tag CONTAINS ['high_value','core']",
  "sort": "-created_at",
  "page_token": "eyJvZmZzZXQiOjEwMDB9", "limit": 100
}
```

**筛选 DSL（EBNF 摘要）**：

```
filter := orExpr
orExpr := andExpr (('OR'|'||') andExpr)*
andExpr := primary (('AND'|'&&') primary)*
primary := '(' orExpr ')' | predicate
predicate := path op value
path := IDENT ('.' IDENT)*
op := '==' | '!=' | '>' | '>=' | '<' | '<=' | 'IN' | 'NOT IN' | 'CONTAINS' | 'BETWEEN' | 'EXISTS'
value := STRING | NUMBER | BOOLEAN | 'null' | '[' value (',' value)* ']'
```

规则：path 必须命中对象 Schema 的白名单字段（`customer.attributes.*` 动态字段经 jsonb 路径）；`CONTAINS` 映射 jsonb 数组/标签、`BETWEEN` 映射范围；**禁止**任意 SQL 注入 —— DSL 词法/语法解析 + Schema 校验 + 参数化绑定。

**响应**：

```json
{
  "code": 0, "message": "ok", "request_id": "req_123",
  "data": {
    "items": [{"id": "c_1001", "status": "active", "tags": ["high_value"], "phone_last4": "****1234"}],
    "next_page_token": "eyJvZmZzZXQiOjExMDB9", "total": 12400
  }
}
```

**分页**：cursor（首查可带 `filter` 与 `limit`，返回 `next_page_token`）；不做 OFFSET 大翻页。

### 3.3 链接与对象图

- 链接元数据注册：`LinkDef(fromType, linkName, toType, joinTable, joinColumns)` —— 本项目核心链接：`customer ∈ audience`、`delivery → customer / campaign`。**A：audience_member 仅承载 STATIC 人群（见 8.1 mode）**；DYNAMIC 人群的成员链接 = `RuleEngine` 实时执行 rule 的派生查询，不落成员表。
- `links()` 查询自动 join 并注入租户过滤（成员表带 `tenant_id`）；DYNAMIC 人群 `links()` 走实时派生并附加模式校验。
- 写链接（导入人群成员）经 Action `updateCustomerState` / 人群管理接口，不开放裸 SQL；**导入成员仅允许 `mode = STATIC` 的人群，DYNAMIC 人群写成员 → 拒绝（E-12006）**；DYNAMIC 人群成员预览 = 执行 rule 的实时查询。

### 3.4 Action 框架

**接口与抽象基类**：

```java
public interface Action<R extends ActionRequest> {
    ActionMeta meta();                       // name / description / inputSchema(JSON)
    ActionResult execute(ActionContext ctx, R req);
}
public abstract class AbstractAction<R extends ActionRequest> implements Action<R> {
    @Override public final ActionResult execute(ActionContext ctx, R req) {
        // 模板方法：管线不可跳过（对应架构 4.3）
        pipeline(ctx, req);          // 1 鉴权 2 租户 3 幂等 4 业务 5 配额（Validator 链）
        ActionResult result = doExecute(ctx, req);
        audit(ctx, req, result);     // 6 全量审计（异步写 action_log）
        return result;
    }
    protected abstract ActionResult doExecute(ActionContext ctx, R req);
}
```

**校验管线 SPI**（顺序固定、可扩展）：

```java
public interface ActionValidator { void validate(ActionContext ctx, ActionRequest req); }
// 内置实现（Bean 顺序）：
//  AuthenticationValidator  RBAC + 对象归属
//  TenantValidator         租户上下文一致（E-11002）
//  IdempotencyValidator    (tenant_id, request_id) 去重（E-13003）
//  BusinessValidator       频控(E-13004) / 退订(E-13005) / 时段(E-13007) / 模板审核状态
//  QuotaValidator          租户触达量/费用上限（E-13006）
```

**ActionRegistry**：`Map<String, Action<?>>`，应用启动时扫描 `@ActionDef(name, description, inputSchema)` 注册；`sendTouch / createCampaign / pauseCampaign / updateCustomerState / importEvents` 五个内置 Action 的入参/校验/副作用与架构文档 4.3 表一致。

**执行上下文**：

```java
public record ActionContext(Long tenantId, Long userId, String role,
                            String requestId, Map<String,Object> attributes) {}
```

### 3.5 事件模型与业务事件总线（EA-Bus）

**术语约定（B）**：本系统两套独立事件机制，不可混称「事件流」—— 业务事件总线（EA-Bus：Redis Stream `ea:events` / `ea:touch`，驱动触达闭环，本小节 3.5）与 Agent 会话事件（agentscope 31 类 → SSE 渲染，4.6，驱动前端实时交互）。回执复盘接线见 10.1 步骤 10。

**事件结构**：

```java
public record EventRecord(Long id, Long tenantId, String customerId,
                          String eventType, Map<String,Object> payload,
                          String dedupKey, Instant createdAt) {}
```

**导入**：`importEvents` Action → `EventService.ingest()`：幂等（`(tenant_id, dedup_key)` 唯一，冲突跳过）→ 写 `event` 表 → `XADD ea:events`（stream，field=JSON，含 `tenant_id` 便于消费端过滤）。

**消费**：Redis Stream 消费组 `ea:events` / group `ea:consumer`：

```
消费端读取(XREADGROUP) → 反序列化 → 按租户上下文重建(5.5)
→ 匹配自动触达规则（campaign.trigger_rule jsonb，详细设计补充字段，见 8.4）
→ 触发 sendTouch(campaign 维度去重) → XACK
失败：XACK 忽略错误消息 → 写死信 ea:events:dlq + 告警
```

消费端幂等：每消息按 `(tenant_id, dedup_key)` 再次确认（容错「消费成功但未 ACK」重放）。

**消费匹配冷却窗**（`trigger_rule.cooldown`，如 `{"cooldown":"1h"}`）：命中规则触发 `sendTouch` 前，SETNX `ea:cd:{tenant}:{campaign}:{customerId}`（TTL = cooldown 时长）——已存在即跳过（同客户同任务去重，数据流 7.2 行「同客户同任务」的载体）；SETNX 成功才入触达队列。冷却窗键由消费端创建、到期自动失效，不落库（仅运行时抑制；`delivery` 表仍记录实际触达实例供审计）。

### 3.5.1 AB 实验（实验分组与度量）

演进路线「阶段三：灰度/AB」（架构 12）中 AB 部分的设计定案：

**模型**：campaign 级 AB 实验，扩展 `campaign` 三列（8.1），不新建实验表——实验是任务编排属性而非独立实体，与灰度（gray_ratio）同层：

```java
campaign.ab_mode     varchar(8)  NOT NULL DEFAULT 'NONE'  -- NONE|AB
campaign.ab_split    smallint    NOT NULL DEFAULT 0       -- 变体总占比 1-99（CONTROL 吃剩余）
campaign.ab_variants jsonb       NOT NULL DEFAULT '[]'    -- 变体数组，见下
```

```jsonc
// ab_variants：1-3 个变体，按序对应 bucket 区间 [0, s1) [s1, s2) …
[
  {"name": "A", "channel": "email", "template_id": 12,
   "frequency_limit": {"max_per_day": 2}, "gray_ratio": 100},
  {"name": "B", "channel": "sms",   "template_id": 34}
]
```

- **变体只允许策略差异**：channel / template_id / frequency_limit / gray_ratio 覆盖；人群（audience_id）与触发规则（trigger_rule）固定为主 campaign 配置——保证组间可比（单变量归因约束：一次实验只改一个策略维度，多变量同时改则结论不可归因）。
- **对照组 = 主 campaign 配置**（非变体覆盖）：bucket ≥ 变体总占比 的客户走原 channel / template_id / frequency_limit——对照「当前线上策略」。

**分桶**（消费匹配管线，10.3 步骤 5，位于灰度过滤之后）：

```
冷却窗 SETNX → 灰度过滤（gray_ratio 抽样，命中写 delivery.gray_hit）
→ 确定性分桶：bucket = SHA256(tenant_id || campaign_id || customer_id) 取模 100
   bucket ∈ [0, s1) → TREATMENT_A；[s1, s2) → TREATMENT_B；…；≥ 总占比 → CONTROL
→ 变体配置覆盖 → sendTouch → delivery.ab_group 落库（组别审计）
```

- **确定性 hash 分桶**（非随机抽样）：同一客户在重试 / 复盘 / 多次触发下分组稳定，且无跨组重复（一个 customer 只进一组）；冷却窗在 campaign 维度，同客户同任务只发送一次，与分组正交。
- `delivery.ab_group varchar(16) NULL`：NULL=非实验，CONTROL / TREATMENT_A / TREATMENT_B / TREATMENT_C。

**度量与复盘**：
- `GET /api/campaigns/{id}/ab-report`（7.1）：按 `ab_group × delivery.status` 聚合 + 后续转化事件计数（event 按 customer_id 关联，`trigger_rule.conversion_event` 如 `order_paid`）——前端实验面板渲染组间对比（7.3）。
- Agent 复盘（10.1 步骤 10）：`queryDelivery` 支持 `ab_group` 过滤，对比各组触达成功率 / 转化率后给出实验结论，写入 agent_run（决策痕迹）。

**审批**：AB 配置（开关 / split / 变体）任一变更与实验启动走既有 campaign 审批（4.4）；变体引用新模板仍走模板审核门控。

### 3.6 调度器

- 框架：Spring `@Scheduled` + `RedisLock` 分布式互斥（多实例防重复触发）。
- 任务：`campaign.status = SCHEDULED` 到期启动 → `RUNNING`；周期任务按 `campaign.schedule`（cron 表达式）；暂停 `PAUSED` 跳过。
- 心跳/恢复：调度器启动时把 `RUNNING` 且 `updated_at` 超时的任务标记 `FAILED`（进程崩溃兜底）。

---

## 4. AI-Agent 层详细设计

### 4.1 agentscope 装配

**依赖**（Maven）：

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-harness</artifactId>
  <version>2.0.1</version>
</dependency>
<!-- 模型扩展按接入方选择其一或全部 -->
<dependency><groupId>io.agentscope</groupId><artifactId>agentscope-extensions-model-dashscope</artifactId></dependency>
<dependency><groupId>io.agentscope</groupId><artifactId>agentscope-extensions-model-openai</artifactId></dependency>
```

**装配要点**：

- `ModelRegistry`：模型别名 → Model 实例（API Key 从环境变量/托管密钥读取，不落库）。
- `AgentConfig`：会话超时、最大工具调用轮次（防无限循环）、工作目录（Workspace 沙箱）。
- 核心抽象：ReActAgent（agentscope-core）承载 4.2 工具 + 推理循环；HarnessAgent 承载运行与事件输出。
- 会话与记忆后端：`RuntimeContext(sessionId, userId)` 绑定租户（AgentSession 记录 tenantId，见 4.4）。

### 4.2 工具层设计

**工具接口**（每个工具实现一次，注册进 ToolRegistry）：

```java
public interface AgentTool {
    String name();
    String description();                 // LLM 选择依据
    String inputSchema();                 // JSON Schema
    Object invoke(Map<String, Object> args);   // 实现内注入租户过滤 + 归属校验
}
```

**内置工具清单**（与架构文档 5.2 三件套一致）：

| 名称 | inputSchema 要点 | invoke 实现 |
|---|---|---|
| queryCustomers | filter / sort / page_token / limit | ObjectApiService.search(CUSTOMER) + LLM 脱敏视图 |
| queryAudience | audience_id 或 filter | 人群 + 成员统计 |
| getCampaign | campaign_id | 任务状态 |
| queryDelivery | campaign_id / customer_id / status | 触达记录 |
| queryEvents | customer_id / event_type / 时间窗 | 事件查询 |
| callFunction | name / args | 委托 FunctionRegistry（只读咨询函数注册表，与 ActionRegistry 对称）：audienceStats / frequencyCheck / channelPreference / churnRiskScore（流失预测）/ bestSendTime（最优发送时段优化） |
| applyAction | action / args | 委托 ActionRegistry（权限下放 + 审批门控，见 4.4） |

**职责边界（H）**：Function（audienceStats / frequencyCheck / churnRiskScore / bestSendTime 等）仅供决策咨询，返回只读数据，不做任何强制；强制约束（频控 / 退订 / 时段 / 模板审核）一律在 Action 管线 Validator 链执行（3.4），绝不依赖 LLM 自觉调用。

**MCP 接入**：本系统内置工具经 agentscope 工具注册表直连；外部数据源/通道工具按 MCP 协议（`mcp://` endpoint 扫描 + 工具描述拉取）接入，统一走 `ToolRegistry`，LLM 侧无感知差异。

### 4.3 会话状态机

**状态与转移**（对应架构 5.3）：

```java
public enum AgentRunStatus { NEW, PLANNING, AWAITING_APPROVAL, EXECUTING, OBSERVING, COMPLETED, FAILED, CANCELLED }
```

| 当前 | 事件 | 到达 | 触发条件 |
|---|---|---|---|
| NEW | start | PLANNING | 会话创建 |
| PLANNING | plan_ready | AWAITING_APPROVAL | 步骤含高危动作（全量触达/删除）或建议模式 |
| PLANNING | plan_ready | EXECUTING | 自动模式且全部步骤 ≤ 授权范围 |
| AWAITING_APPROVAL | approve | EXECUTING | 审批通过（审核员/管理员，Action 级别校验） |
| AWAITING_APPROVAL | reject | CANCELLED | 审批拒绝 |
| EXECUTING | tool_done | OBSERVING | 一步工具/动作完成 |
| OBSERVING | next_step | PLANNING | 还有后续步骤 |
| OBSERVING | all_done | COMPLETED | 全部步骤完成 |
| 任意 | error | FAILED | 不可恢复错误（含 LLM 调用失败超限） |
| 任意 | cancel | CANCELLED | 用户取消/超时 |

实现：`AgentStateMachine`（状态表驱动，非法转移抛 E-15002）；每次转移写 `agent_run` 状态与时间戳 + 发 Agent 会话事件。

### 4.4 审批门控

- **判定**：`applyAction` 工具调用前，按「Agent 权限 = 发起用户 RBAC 下放」（7 章）推断；动作 ∈ {全量触达（灰度=100%）、delete 类} 或建议模式 → 进入 AWAITING_APPROVAL。
- **请求模型**：`ApprovalRequest(runId, actionName, argsSummary, dangerLevel, requestedAt)` 写入 `agent_run.decisions` 并置状态。
- **审批接口**：`POST /api/agent/runs/{runId}/approval {decision: APPROVE|REJECT, actorId}`；权限：操作者 ≥ 发起用户角色 且 具备该 Action 权限；并发用 RedisLock 防重复审批。
- **超时**：审批超时（默认 10 分钟）自动拒绝 → CANCELLED。

### 4.5 记忆三机制

| 层 | 实现 | 结构 |
|---|---|---|
| 长期 | PostgreSQL（customer.attributes / 租户偏好） | 跨会话、变更经 updateCustomerState |
| 短期 | Redis Hash `ea:session:{sessionId}` | 会话上下文、TTL 24h |
| 工作 | Redis Hash `ea:run:{runId}:steps` | 本轮步骤与中间结果、TTL 任务级（默认 1h，失败回收） |

上下文压缩：会话超 20 轮时，旧轮次摘要写入 `ea:session:{sessionId}:summary`，原文保留在 `agent_run.decisions` 供审计。

### 4.6 Agent 会话事件与 SSE 协议（详细契约）

本小节对应架构 6.3 —— agentscope 31 类 Agent 会话事件经此通道渲染到前端；与 3.5 业务事件总线（EA-Bus）相互独立。复盘结果的会话注入也复用本通道（10.1 步骤 10）。

`/api/agent/chat` 两段式契约（7.1/7.4）：**POST** 发起会话（建 `agent_run` + `AgentSession`，返回 run_id，不等流）→ **GET `?request_id=…`**（EventSource）订阅该会话事件流；刷新/断线重连只重订阅 GET，不重复发起会话。响应头：`Content-Type: text/event-stream`；心跳 `:ping` 每 15s。

| event | data 字段 | 触发点 |
|---|---|---|
| plan | runId / steps[{id,desc}] | 规划完成 |
| thinking_delta | runId / delta | 推理流式输出 |
| tool_call | runId / tool / status(start\|done\|error) / args(脱敏) / result(摘要) | 工具调用边界 |
| approval_required | runId / action / argsSummary / dangerLevel / deadline | 进入审批 |
| action_result | runId / action / status(accepted\|rejected) / deliveryId | Action 提交结果 |
| text_delta | runId / delta | 面向用户的中文输出 |
| done | runId / agentRunId / status | 会话结束 |

示例（发送触达）：

```
event: plan
data: {"run_id":"run_88","steps":[{"id":"s1","desc":"查询流失人群"},{"id":"s2","desc":"发送触达"}]}

event: tool_call
data: {"run_id":"run_88","tool":"queryAudience","status":"done","args":{"filter":"status == 'active' AND last_order_at < '2026-08-01'"}}

event: approval_required
data: {"run_id":"run_88","action":"sendTouch","argsSummary":"人群#a_3，渠道 sms，模板 t_7，量 12400","danger_level":"HIGH","deadline":"2026-09-04T12:00:00Z"}

event: action_result
data: {"run_id":"run_88","action":"sendTouch","status":"accepted","delivery_id":"d_9001"}

event: done
data: {"run_id":"run_88","agent_run_id":"ar_55","status":"COMPLETED"}
```

---

## 5. 租户上下文机制详细设计

**第一条原则（G）：每个租户 = 一个独立 Ontology 实例** —— 对象集、链接、Action 权限、dynamic security 均在其内；本章全部机制（识别 / 强制过滤 / 归属校验 / 数据级规则）服务于该原则。

### 5.1 TenantContext（ea-common）

```java
public final class TenantContext {
    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();
    public record Principal(Long tenantId, Long userId, String role) {}
    public static Principal require();          // 缺失抛 BizException(11001)
    public static void set(Principal p);
    public static void clear();
}
```

**异步传播**：线程池统一 `TaskDecorator`（提交时拷贝、执行后清理）；MQ/Stream 消费端从消息字段重建（消息始终携带 tenantId）。

### 5.2 识别与注入（网关过滤器）

1. 解析 Host（子域名/自定义域名）→ 租户候选；未命中回退登录态声明
2. 校验登录态（JWT 签名 + 租户绑定，防跨租户 token）
3. 校验租户状态（停用 → E-11003）
4. 注入 `X-Tenant-Id` 请求头 → `TenantContext.set(Principal)`
5. 对所有 `/api/**` 强制执行（白名单：登录、健康检查、回调验签类端点除外，回调另设租户解析）

### 5.3 数据访问强制（读写双闸）

- 读：应用层统一强制 `tenant_id = ctx.tenantId` 条件（Mapper 基类 / Service 约定），业务代码不可绕过；TenantContext 缺失即拒绝（E-11001），不放行全表。
- 写：写路径显式携带 `tenant_id`（取自 TenantContext，禁止客户端传参覆盖）；DDL 复合 FK `REFERENCES x(tenant_id, id)` 兜底跨租户引用。
- 兜底：`SELECT` 白名单表（`tenant / tenant_user / unsubscribe` 平台级表）由平台角色访问，其余表一律强制过滤。

### 5.4 对象归属校验

`ObjectOwnershipChecker.assertOwned(type, id)`：查询强制带 `tenant_id = ctx.tenantId`，查不到即抛 E-12002（防跨租户引用/遍历）。应用于：对象 API 单查、Action 引用参数（LLM 生成的 customer_id / campaign_id / audience_id）、链接操作。

### 5.5 跨边界传递清单

| 边界 | 机制 |
|---|---|
| HTTP → 服务 | X-Tenant-Id → TenantContext |
| 服务 → DB | 应用层显式 `tenant_id` 条件 + 复合 FK 兜底 |
| 服务 → 异步线程池 | TaskDecorator |
| 服务 → Redis 队列/Stream | 消息内嵌 tenantId，消费端重建 |
| Agent 会话 → 工具 | AgentSession.tenantId → 工具实现内过滤 |
| 外部回调 | 验签后按回调路由表映射租户 |

---

## 6. 通道层详细设计

### 6.1 ChannelAdapter

```java
public interface ChannelAdapter {
    String channelType();                       // sms | email | wechat | push | console
    void validate(ChannelConfigDto cfg);        // 连通性/配置完整
    SendResult send(CustomerDto customer, TemplateDto template,
                    Map<String, Object> vars, DeliveryContext delivery);   // 明文内容在服务端组装
    ReceiptStatus queryReceipt(String channelMsgId);
}
```

实现：`SmsChannelAdapter` / `EmailChannelAdapter` / `WechatChannelAdapter` / `PushChannelAdapter` / `ConsoleChannelAdapter`（降级联调用，发送结果写日志，不发真实消息）。

### 6.2 注册与配置读取

- `ChannelAdapterRegistry`：启动扫描 `@ChannelType(type)` 注册。
- 配置：`channel_config`（config_encrypted 密文 + enabled + frequency_limit jsonb）；发送前 `ConfigCrypto.decrypt()`（9.3 信封解密），**明文仅存在于调用栈内，不落日志、不落库**。

### 6.3 回执回调

- 端点：`POST /api/channels/{type}/callback`（各通道回调地址 = 网关统一入 + 通道类型路由）。
- 验签：HMAC（租户级 secret = `channel_config.callback_secret` 信封解密（9.3），头部 `X-Signature`），失败 E-14004；回调按验签主体映射租户（5.5）。
- 幂等：`(tenant_id, channel_msg_id)` → 更新 delivery；重复回调幂等跳过。
- 状态映射：`SENT → DELIVERED / BOUNCED / FAILED`；BOUNCED/FAILED 触发重试（6.4）。

### 6.4 重试与降级

- 发送失败：指数退避（1s/4s/16s，最多 3 次）→ 仍失败标记 `FAILED` + EA-Bus 告警事件。
- 通道级熔断：连续失败率超阈值（如 50 次调用 20% 失败）→ 熔断 60s（`ea:cb:{channel}` Redis 计数）。
- 降级：租户未配置真实通道 → ConsoleChannelAdapter 输出日志，功能可联调。

---

## 7. 应用层详细设计

### 7.1 REST API 完整清单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /api/auth/login | 公开 | 登录换 JWT（access 2h / refresh 7d） |
| GET | /api/customers | customer.read | 客户列表（对象 API 封装） |
| GET | /api/customers/{id} | customer.read | 客户画像 |
| GET/POST | /api/audiences | audience.read/write | 人群 CRUD |
| GET | /api/audiences/{id}/members | audience.read | 成员预览 |
| POST | /api/audiences/{id}/members | audience.write | 导入成员（建链接） |
| GET/POST/PATCH | /api/campaigns | campaign.read/write | 任务 CRUD |
| POST | /api/campaigns/{id}/pause | campaign.write | 暂停（= pauseCampaign） |
| POST | /api/campaigns/{id}/resume | campaign.write | 恢复 |
| POST | /api/campaigns/{id}/trigger | campaign.write | 手动立即触达 |
| GET | /api/campaigns/{id}/ab-report | campaign.read | AB 实验组间对比聚合（3.5.1：ab_group × status + 转化事件） |
| GET/POST/PATCH | /api/templates | template.read/write | 模板 CRUD |
| POST | /api/templates/{id}/submit | template.write | 提交审核 |
| POST | /api/templates/{id}/review | template.review | 审核通过/驳回 |
| GET/POST/PATCH | /api/channels | channel.read/write | 通道配置（凭据脱敏回显） |
| POST | /api/channels/{id}/test | channel.write | 连通性测试 |
| GET | /api/deliveries | delivery.read | 触达记录查询（监控） |
| POST | /api/events | event.import | 导入业务事件（= importEvents） |
| POST | /api/unsubscribe | customer.write | 主动退订登记（9.5：代客退订 / 客户自助） |
| POST | /api/mfa/challenge | 登录态 | 获取 MFA 挑战（返回 challengeId + TTL，9.1） |
| POST | /api/mfa/verify | 登录态 | 校验挑战码 → 短期 mfa_token（敏感端点携带 `X-MFA-Token`，9.1） |
| POST | /api/agent/chat | agent.chat | Agent 对话：发起会话（建 agent_run + AgentSession），返回 run_id（4.6） |
| GET | /api/agent/chat | agent.chat | SSE 事件流订阅：`?request_id=…` 拉取该会话事件（7.4 两段式契约） |
| POST | /api/agent/runs/{id}/approval | agent.approve | 审批决策（4.4） |
| GET | /api/agent/runs | agent.chat | 会话/运行历史（审计回放） |
| GET/POST | /api/tenant/* | tenant.manage | 平台侧：租户/配额/计量 |

统一约定：结果包装 `Result`；所有写操作带 `request_id` 头实现幂等。

### 7.2 认证与会话

- JWT claims：`{sub: userId, tenantId, role, jti}`；Refresh Token 服务端存 Redis（可吊销）。
- 会话（Agent）：`AgentSession(sessionId, userId, tenantId, status)` 存 Redis，TTL 24h。

### 7.3 前端模块与组件树（Vue3 + TS + Element Plus）

```
App
├── Layout (Sidebar / Header / TenantInfo)
│   ├── CustomerList → CustomerDetail(画像卡/触达历史/标签/UnsubscribeButton 退订登记)
│   ├── AudienceList → AudienceRuleEditor(DSL 规则构建器/成员预览)
│   ├── CampaignList → CampaignCanvas(人群×渠道×模板×时间×灰度画布/实验面板：AB 变体编辑 + split + 组间对比视图)
│   ├── TemplateList → TemplateEditor(var 变量校验)
│   ├── ChannelList → ChannelEditor(凭据录入/连通性测试)
│   ├── DeliveryMonitor(实时量/到达率/回执分布/失败明细)
│   ├── AgentWorkbench
│   │   ├── ChatPanel(SSE EventSource)
│   │   ├── CardRenderer(plan/tool/action/table/chart 卡片)
│   │   ├── ApprovalDialog(审批按钮/风险等级标识/MFA 校验输入，9.1)
│   │   └── RunHistoryPanel(agent_run 回放)
│   └── TenantAdmin(平台侧：租户/配额 仪表板)
└── api/  (axios 封装：拦截器注入 token / requestId / 错误码映射)
```

### 7.4 SSE 前端消费

- 传输定案：**EventSource 原生 GET** —— `new EventSource('/api/agent/chat?request_id=…')`（会话标识经 query 传入，服务端 SSE 端点只读流式输出，不做 POST 兼容分支）；心跳 `:ping` 15s 保活。
- 事件分派：`plan → CardRenderer`；`thinking_delta / text_delta → ChatPanel` 流式追加；`tool_call → 工具状态角标`；`approval_required → ApprovalDialog`；`done → 落盘 RunHistoryPanel`。

---

## 8. 数据架构详细设计

### 8.1 物理 DDL（PostgreSQL 15+，字段级）

```sql
CREATE TABLE tenant (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name          varchar(128) NOT NULL,
  domain        varchar(255) UNIQUE NOT NULL,          -- 子域名/自定义域名
  plan          varchar(16)  NOT NULL DEFAULT 'free',
  status        varchar(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE|DISABLED
  quota         jsonb NOT NULL DEFAULT '{}',           -- 触达/AI/存储配额
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant_user (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  login_name    varchar(64) NOT NULL,
  name          varchar(64)  NOT NULL,
  role          varchar(16)  NOT NULL,  -- OPERATOR|REVIEWER|ADMIN（PLATFORM_ADMIN 属平台侧，不在此表）
  status        varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, login_name),
  UNIQUE (tenant_id, id)                -- 供业务表复合 FK（8.4 数据完整性要点）
);

CREATE TABLE customer (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  external_id   varchar(64),
  phone         varchar(32),
  email         varchar(128),
  wechat_openid varchar(64),
  attributes    jsonb NOT NULL DEFAULT '{}',   -- 画像/标签/偏好渠道，GIN 索引
  status        varchar(16) NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, external_id),
  UNIQUE (tenant_id, id)                -- 供 audience_member / delivery / event 复合 FK（8.4）
);

CREATE TABLE audience (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  name          varchar(128) NOT NULL,
  mode          varchar(8)  NOT NULL DEFAULT 'DYNAMIC',  -- DYNAMIC(规则派生)|STATIC(成员表)，二选一（A）
  rule          text,                                    -- DYNAMIC 人群筛选 DSL（3.2）；STATIC 人群必须为空
  owner_id      bigint NOT NULL REFERENCES tenant_user(tenant_id, id),  -- dynamic security 归属（9.2，D）；创建时 = 当前用户
  status        varchar(16) NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),               -- 供 campaign / audience_member 复合 FK（8.4）
  CONSTRAINT chk_audience_mode CHECK (
    (mode = 'DYNAMIC' AND rule IS NOT NULL AND rule <> '') OR
    (mode = 'STATIC'  AND (rule IS NULL OR rule = ''))
  )                                                     -- 模式与 rule 互斥；成员表互斥由应用层强制（3.3）
);

CREATE TABLE audience_member (   -- 仅 STATIC 人群使用；DYNAMIC 人群成员实时派生、不落此表（A）
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  audience_id   bigint NOT NULL REFERENCES audience(tenant_id, id),
  customer_id   bigint NOT NULL REFERENCES customer(tenant_id, id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, audience_id, customer_id)
);

CREATE TABLE campaign (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  name          varchar(128) NOT NULL,
  audience_id   bigint NOT NULL REFERENCES audience(tenant_id, id),
  channel       varchar(16) NOT NULL,           -- sms|email|wechat|push
  template_id   bigint NOT NULL REFERENCES template(tenant_id, id),
  schedule      timestamptz,                    -- 一次性时间；周期任务用 cron
  cron          varchar(64),
  gray_ratio    int NOT NULL DEFAULT 100,       -- 灰度百分比；审批判定「全量=100」（4.4）
  ab_mode       varchar(8)  NOT NULL DEFAULT 'NONE', -- NONE|AB 实验模式（3.5.1）
  ab_split      smallint    NOT NULL DEFAULT 0,      -- 变体总占比 1-99，CONTROL 吃剩余
  ab_variants   jsonb       NOT NULL DEFAULT '[]',   -- 变体数组（channel/template_id/frequency_limit/gray_ratio 覆盖，1-3 个）
  owner_id      bigint NOT NULL REFERENCES tenant_user(tenant_id, id),  -- dynamic security 归属（9.2，D）
  trigger_rule  jsonb,                          -- 事件触发规则（详细设计补充，见 8.4）
  status        varchar(16) NOT NULL DEFAULT 'DRAFT', -- DRAFT|SCHEDULED|RUNNING|PAUSED|FINISHED|FAILED
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_campaign_gray CHECK (gray_ratio BETWEEN 0 AND 100),
  CONSTRAINT chk_campaign_ab CHECK (
    ab_mode = 'NONE' OR (ab_split BETWEEN 1 AND 99 AND jsonb_typeof(ab_variants) = 'array'
                         AND jsonb_array_length(ab_variants) BETWEEN 1 AND 3)
  ),                                            -- 实验模式时 split 1-99 且 1-3 个变体（3.5.1）
  UNIQUE (tenant_id, id)                        -- 供 delivery 复合 FK（8.4）
);

CREATE TABLE template (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  channel       varchar(16) NOT NULL,
  title         varchar(256),
  content       text NOT NULL,
  vars          jsonb NOT NULL DEFAULT '[]',    -- 变量名清单，渲染前校验
  review_status varchar(16) NOT NULL DEFAULT 'DRAFT', -- DRAFT|PENDING|APPROVED|REJECTED
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id)                -- 供 campaign / delivery 复合 FK（8.4）
);

CREATE TABLE channel_config (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  channel       varchar(16) NOT NULL,
  config_encrypted text NOT NULL,               -- 信封加密密文（9.3）
  enabled       boolean NOT NULL DEFAULT true,
  frequency_limit jsonb NOT NULL DEFAULT '{}',  -- {max_per_day, quiet_hours}
  callback_secret text,                        -- 回执验签 HMAC secret（信封加密密文，同 config_encrypted，9.3；明文不落库/日志）
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, channel)
);

CREATE TABLE delivery (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id    varchar(64) NOT NULL,           -- 幂等键
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  campaign_id   bigint REFERENCES campaign(tenant_id, id),
  customer_id   bigint NOT NULL REFERENCES customer(tenant_id, id),
  channel       varchar(16) NOT NULL,
  template_id   bigint REFERENCES template(tenant_id, id),
  channel_msg_id varchar(128),                  -- 通道侧消息 ID（回执关联）
  gray_hit      boolean NOT NULL DEFAULT false, -- 灰度抽样命中审计（E）
  ab_group      varchar(16),              -- AB 组别：NULL=非实验|CONTROL|TREATMENT_A/B/C（3.5.1）
  status        varchar(16) NOT NULL DEFAULT 'PENDING',
                -- PENDING|SENT|DELIVERED|BOUNCED|FAILED|UNSUBSCRIBED
  error         text,
  attempt       int NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, request_id),
  UNIQUE (tenant_id, channel_msg_id)   -- 回执回调幂等兜底（6.3/10.1 步骤 9）；NULL 不冲突
);

CREATE TABLE event (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  customer_id   bigint REFERENCES customer(tenant_id, id),
  event_type    varchar(64) NOT NULL,
  payload       jsonb NOT NULL DEFAULT '{}',
  dedup_key     varchar(128) NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, dedup_key)
);

CREATE TABLE unsubscribe (                      -- 平台级全局退订总表（架构 8.4 落地）
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),  -- 登记退订的租户（归属记录，非隔离键）
  customer_key  varchar(128) NOT NULL,          -- hash(phone|email|openid)，不含明文
  channel       varchar(16) NOT NULL,
  reason        varchar(256),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (customer_key, channel)                -- 全局唯一：任一租户退订即全平台生效（跨租户查重直接命中）
);

CREATE TABLE agent_run (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  session_id    varchar(64) NOT NULL,
  user_id       bigint NOT NULL REFERENCES tenant_user(tenant_id, id),
  goal          text NOT NULL,
  plan          jsonb,                          -- 规划步骤
  decisions     jsonb,                          -- 决策与审批全程（审计回放）
  status        varchar(16) NOT NULL DEFAULT 'NEW',
                -- NEW|PLANNING|AWAITING_APPROVAL|EXECUTING|OBSERVING|COMPLETED|FAILED|CANCELLED（4.3 状态机）
  tokens_used   bigint NOT NULL DEFAULT 0, -- 会话累计 LLM token 用量（计量，7.1/10.2 步骤 7）
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE action_log (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id    varchar(64) NOT NULL,
  tenant_id     bigint NOT NULL REFERENCES tenant(id),
  actor_type    varchar(8)  NOT NULL,           -- USER|AGENT|SYSTEM
  actor_id      varchar(64) NOT NULL,
  action        varchar(64) NOT NULL,
  args          jsonb,                          -- 脱敏后参数
  result        jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);
```

### 8.2 索引

```sql
CREATE INDEX idx_customer_tenant        ON customer (tenant_id, status, updated_at);
CREATE INDEX idx_customer_attr          ON customer USING GIN (attributes);
CREATE INDEX idx_campaign_tenant_status ON campaign (tenant_id, status, schedule);
CREATE INDEX idx_campaign_tenant_aud    ON campaign (tenant_id, audience_id);                -- 人群页任务列表
CREATE INDEX idx_delivery_tenant_camp   ON delivery (tenant_id, campaign_id, created_at DESC);
CREATE INDEX idx_delivery_status        ON delivery (tenant_id, status, created_at DESC);
CREATE INDEX idx_event_tenant_type      ON event (tenant_id, event_type, created_at DESC);
CREATE INDEX idx_event_tenant_customer  ON event (tenant_id, customer_id, created_at DESC);  -- Agent 复盘 queryEvents / 客户事件史
CREATE INDEX idx_agent_run_tenant       ON agent_run (tenant_id, session_id, created_at DESC);
CREATE INDEX idx_action_log_tenant      ON action_log (tenant_id, created_at DESC);
CREATE INDEX idx_audience_member       ON audience_member (tenant_id, customer_id);
```

### 8.3 分区与归档

- `delivery` 按月 RANGE 分区（`created_at`），热区近 3 个月；旧分区归档到冷存储、查询走归档副本。分区需预建：调度任务每月提前创建下月分区（如每月 25 日 `CREATE TABLE delivery_y2026m10 PARTITION OF delivery …`）。
- 归档任务（每日）：`event` 超保留期（默认 90 天）→ 转存 `event_archive` 归档表（同结构 + `archived_at timestamptz`，无活动 FK、带 tenant_id 供跨租户查询）→ 删原表；`agent_run / action_log` 长期保留（合规），两年以上分区压缩。
- 迁移：Flyway 版本化脚本管理 DDL（V1__init.sql …），生产变更走新版本脚本。

### 8.4 详细设计补充字段说明

- `campaign.trigger_rule`：事件驱动触发规则（`{"event_type":"order_placed","window":"1d","cooldown":"1h"}`），供 3.5 事件消费端匹配。
- `unsubscribe` 表：**平台级全局退订总表**——任一租户内客户退订即全平台生效；`UNIQUE (customer_key, channel)` 全局唯一，跨租户查重直接命中（`customer_key` 为哈希，不含明文手机号）。`tenant_id` 仅记录登记租户（归属审计），不是隔离键；退订检查发生在业务校验（9.5），先查本租户、再查全局表。
- **数据完整性要点**（本次 review 加固）：
  - **复合 FK 跨租户拦截**：业务表间引用一律 `REFERENCES x(tenant_id, id)`（配合目标表 `UNIQUE (tenant_id, id)`）——DDL 层面杜绝租户 B 记录引用租户 A 对象（campaign→audience/template、delivery→campaign/customer/template、audience_member→audience/customer、event→customer）；「租户 A 的 token 查租户 B 对象」由 5.4 归属校验（E-12002）拦截，两者互为纵深。
  - **归属字段**：`audience.owner_id` / `campaign.owner_id` / `agent_run.user_id` `REFERENCES tenant_user(tenant_id, id)`（复合 FK，杜绝跨租户 owner），创建时 = 当前用户（dynamic security D）。
  - **幂等唯一约束清单**：`delivery(tenant_id, request_id)`（发送）、`delivery(tenant_id, channel_msg_id)`（回执回调，NULL 不冲突）、`event(tenant_id, dedup_key)`（事件导入）。其余写操作（campaign/template 等）由 Redis IdempotencyService（2.2）兜底，不落库。
  - **敏感列**：`channel_config.config_encrypted` 与 `callback_secret` 均为信封加密密文（9.3），明文仅存在于调用栈，不落日志、不落库、回显脱敏。
  - **CHECK 约束**：`chk_audience_mode`（模式-rule 互斥，A）、`chk_campaign_gray`（gray_ratio ∈ [0,100]，支撑 4.4 审批判定「全量=100%」）、`chk_campaign_ab`（ab_mode=AB 时 ab_split ∈ [1,99] 且 1-3 个变体，3.5.1）。
- 其余字段与架构文档 8.2 草案一致，表级对应关系见附录 C。

### 8.5 Ontology 资产与持久化策略

定位：回答「ontology 的规则 / 事件 / 定义是否入库」——按资产层划分存储与变更方式。Palantir Ontology 中「定义（Ontology Definition）是版本化工件、决策痕迹是数据集、派生链接是实时视图」的原则在本系统落地如下：

| 资产层 | 内容 | 存储位置 | 变更方式 | 依据 |
|---|---|---|---|---|
| 定义层（代码工件，**不入业务库**） | TypeRegistry `ObjectTypeDef`、`LinkDef`、ActionRegistry `@ActionDef`、ChannelAdapter/ToolRegistry | 代码，Git 版本化 | 发版（启动扫描注册） | 3.1 / 3.3 / 3.4；编译期类型安全，新增对象类型只加 Def 不改枚举与 Action 代码（C） |
| 规则层（对象属性，**入库**） | `audience.rule`（人群 DSL）、`campaign.trigger_rule`（事件触发）、`channel_config.frequency_limit`（频控/时段） | 对象表 text / jsonb 列（675 / 709 / 739 行） | 运行时对象 API | 规则是租户业务配置而非平台逻辑：运行时变更、随对象行租户隔离、历史可审计；平台逻辑（校验链、频控算法、灰抽样）仍是代码 |
| 实例层（对象数据，**入库**） | 7 对象 ↔ 13 表（附录 C）；STATIC 成员、链接实例 | PostgreSQL | 对象 API / Action | DYNAMIC 成员与链接关系 = RuleEngine 实时派生查询，不落副本（3.3，对应 Palantir Derived Link：「对象是数据的视图，不是副本」） |
| 痕迹层（决策记录，**入库**） | `action_log`（全量审计）、`agent_run.plan / decisions`（决策回放）、`delivery`（触达实例 + gray_hit 灰度审计） | PostgreSQL，不可变，无更新 API | Action 管线第 6 步异步 / 会话状态机落库 | 3.4 / 9.4 / 10.1-10.2；对应 Palantir Action Logs |

**事件双写**（3.5）：`event` 表 = 持久业务对象（复盘 / 客户事件史 / 归档依据），EA-Bus Stream（`ea:events` / `ea:touch`）= 传输队列；Stream 不是业务数据的唯一载体，消费确认与表数据相互印证。

**规则入库而非代码的边界**：凡「租户可运行时调整的业务配置」（人群条件、触发规则、频控时段）一律作为对象属性落库——天然租户隔离 + 审计；凡「平台行为」（校验顺序、幂等算法、灰度抽样、加密）一律留在代码，杜绝用数据表承载平台逻辑（避免出现「规则表 + 规则引擎解释器」的重量级动态化）。

**扩展路径**：当前 7 类型编译期封闭，取类型安全；若未来需免发版运行时建模（运营自助加对象类型），再引入 schema 元数据表（`object_type_def` / `field_def`），届时 TypeRegistry 改为从元数据表装载——8.5 定义层策略随之迁移到实例/规则层同类机制。

---

## 9. 安全详细设计

### 9.1 认证与会话

- 密码：bcrypt（成本因子 10）；登录失败限速（IP + 账号 5 次/10min 锁 30min）。
- JWT：HS256（secret 环境变量注入，长度 ≥ 32 字节）；`jti` 与 Redis 会话绑定实现登出吊销。
- **MFA 流程契约**（敏感端点强制，可配置）：敏感端点 = 通道凭据修改（channel.write 的 PATCH/删除）、审批（agent.approve）、租户配置（tenant.manage）。
  1. 客户端 `POST /api/mfa/challenge`（登录态）→ `{challenge_id, ttl}`（挑战码 6 位随机，TTL 默认 60s，Redis `ea:mfa:{challengeId}`）。
  2. 客户端 `POST /api/mfa/verify {challenge_id, code}` → 校验通过返回短期 `mfa_token`（TTL 300s，一次性，Redis `ea:mfa:token:{userId}`，绑定 challengeId）。
  3. 后续敏感请求头携带 `X-MFA-Token`；网关/拦截器按端点策略校验（`ea.security.mfa.enabled-endpoints`，见 11.1）——缺失/失效 → **E-10004 MFA 校验失败**。
  4. 防爆破：同一账号 5 次/10 分钟校验失败锁定 30 分钟（复用登录限速计数器，E-10004 同码返回）。
  5. 挑战码通道：默认 email（复用模板渲染）或租户配置短信通道；发送走既有触达管线（delivery 记录 `channel=mfa`）以便审计。

### 9.2 RBAC 权限矩阵

| 权限点 | OPERATOR | REVIEWER | ADMIN | PLATFORM_ADMIN |
|---|---|---|---|---|
| customer / audience / campaign / template 读写 | ✅ | ✅ | ✅ | —（平台不触业务） |
| template.review | — | ✅ | ✅ | — |
| channel.read / write | 读 | 读 | ✅ | — |
| delivery.read / event.import | ✅ | ✅ | ✅ | — |
| agent.chat | ✅ | ✅ | ✅ | — |
| agent.approve | — | ✅ | ✅ | — |
| tenant.manage | — | — | — | ✅ |

**数据级规则（dynamic security）**（D）：RBAC 之上、租户之内的对象实例级授权 —— `audience.owner_id` / `campaign.owner_id` 已写入 DDL（8.1）；非 owner 操作仅 ROLE ∈ {REVIEWER, ADMIN} 放行，否则 E-12005；审批门控（4.4）为高危动作的另一道 dynamic security 延伸。

| 对象 | 归属字段 | 规则 |
|---|---|---|
| audience | owner_id | 非 owner：仅 REVIEWER / ADMIN 可读写 |
| campaign | owner_id | 非 owner：仅 REVIEWER / ADMIN 可读写 |

**Agent 权限下放**：`role(agent) = role(发起用户)` 裁剪后的工具白名单；`applyAction` 仅放行 ≤ 用户角色可达的 Action；高危动作（灰度为 100% 的全量触达、删除类）即使 ADMIN 发起也进审批（4.4）。

### 9.3 凭据加密（信封加密）

1. 每个租户独立 Data Key（AES-256-GCM，随机 32B）；Data Key 由主密钥（KMS / 环境变量托管）包裹存储。
2. 存储：`config_encrypted = base64( IV || ciphertext || tag )`；主密钥不落库。
3. 解密时机：发送时在调用栈内解密（6.2），明文不落日志、不落库、不参与审计参数。

### 9.4 审计实现

- 写入点：Action 管线第 6 步（3.4）——`ActionExecutedEvent` 异步落 `action_log`（参数经脱敏：手机号/凭据打码）。
- Agent 审计：`agent_run.plan`（步骤）/ `decisions`（每轮决策理由 + 审批结果 + 工具调用摘要）全程记录，支持回放。
- 不可变：`action_log / agent_run` 无更新 API；admin 查询走只读视图。

### 9.5 频控与退订

- 频控（每客户 × 通道）：Redis 计数键 `ea:fc:{tenant}:{channel}:{customerId}:{date}`（max_per_day）+ 周计数；触发 → E-13004。
- 时段：`frequency_limit.quiet_hours`（如 21:00–09:00 禁发），违反 → E-13007。
- 退订检查：sendTouch 前查 `unsubscribe`（租户级 + 全局 customer_key 匹配）→ E-13005；回执含退订标记时自动写入并更新 delivery.status=UNSUBSCRIBED。
- **主动退订**：`POST /api/unsubscribe {customer_id, channel}`（customer.write；运营代客登记，或客户经「退订 H5」入口自助）——幂等 `(tenant_id, request_id)`；写入 `unsubscribe` 表（`customer_key = hash(phone|email|openid)`，全局唯一 `(customer_key, channel)`），**任一租户登记即全平台生效**；重复退订幂等返回成功；前端入口 = CustomerDetail.UnsubscribeButton（7.3）。与回执退订共用同一写入函数（9.5），保证两种路径语义一致。

### 9.6 LLM 安全

- 工具白名单：ToolRegistry 只暴露声明过的工具；LLM 无法调用未注册操作。
- 对象归属：工具与 Action 内强制 `tenant_id` 匹配（5.4），prompt 注入跨租户 id 一律拒绝。
- 输出校验：LLM 生成的 Action 参数须过 JSON Schema 校验（含枚举/范围），失败 E-16002 并进入反思重试。
- LLM 脱敏视图：工具返回给 LLM 的客户数据对手机号/邮箱做掩码（`138****1234`）；明文仅在 Action 执行的服务端调用栈内使用。
- 上下文隔离：单租户会话只加载本租户语义（模板/偏好/频控），prompt 不含他租户数据。

---

## 10. 关键流程时序

### 10.1 发送触达全链路

```
1. 调用方（运营界面 或 applyAction 工具）→ ActionService.execute(sendTouch, ctx)
2. 网关：租户解析 → TenantContext.set
3. 校验管线：鉴权(RBAC) → 租户(11002) → 幂等(request_id) → 业务(频控/退订/时段/模板审核) → 配额
4. 组装 Delivery(status=PENDING) 落库；(tenant_id, request_id) 唯一约束兜底并发
5. XADD ea:touch:{tenant}（异步队列）
6. 队列消费者（TaskDecorator 重建租户上下文）→ ChannelAdapterRegistry.get(channel)
7. 服务端解密凭据 → 组装明文消息（ChannelMessageBuilder 填模板变量，缺失变量 → 校验失败）
8. adapter.send → 外部通道；成功：delivery.status=SENT + channel_msg_id
   失败：指数退避重试（1s/4s/16s，attempt<=3）→ 仍失败 FAILED + EA-Bus 告警事件
9. 回执回调 POST /api/channels/{type}/callback → HMAC 验签 → (tenant_id, channel_msg_id) 幂等
   → delivery.status=DELIVERED|BOUNCED|FAILED；BOUNCED/FAILED 触发重试策略
10. 更新监控指标（到达率/回执分布）→ 写 EA-Bus（`ea:touch` 回执事件）→ `DeliveryReviewService` 处理：
    按 `delivery.request_id ↔ agent_run.decisions`（触达时记录 request_id）关联会话（B，弱接线）
    → 会话仍活跃：注入「Agent 会话事件」（复盘准备，4.6 通道）；Agent 在 OBSERVING 阶段经
    `queryDelivery` 主动观察回执（10.2 步骤 6）；不活跃：仅落库供审计/前端监控
```

### 10.2 Agent 对话会话

```
1. POST /api/agent/chat（SSE，agent.chat）
2. AgentService：按 (userId, tenantId) 建 AgentSession(Redis)；建 agent_run NEW → start → PLANNING
3. Planner 拆步骤（LLM）→ AgentStateMachine.transition(PLANNING, plan_ready)
   → 需审批？Y: AWAITING_APPROVAL，发 approval_required 事件，等待 (超时自动拒绝)
   → N: EXECUTING
4. 每步 Reasoning 循环：thinking_delta → 选工具（ToolRegistry）→ tool_call 事件
   → 工具执行（租户过滤 + 归属校验 + 脱敏视图）→ action_result 事件 → tool_done → OBSERVING
5. 高危/超阈值动作：applyAction 前权限推断（9.2 下放）→ 进审批（步骤 3 同路径）
6. OBSERVING 反思（回执校验/失败重试）：读取 `agent_run.decisions` 中复盘注入口（10.1 步骤 10 写入）
   或经 `queryDelivery` 主动观察回执 → next_step(PLANNING) 或 all_done
7. 全部完成 → COMPLETED → done 事件；agent_run 落库（plan/decisions 全量 + tokens_used 计量——每轮 LLM 调用的 prompt/completion tokens 由 AgentRunner 累计写入，供租户配额计量（7.1 /api/tenant/*））
8. 任意不可恢复错误 → FAILED；用户取消/超时 → CANCELLED（均发事件 + 落库）
```

### 10.3 事件导入与消费

```
1. POST /api/events（event.import）→ importEvents Action
2. 校验管线 → IdempotencyValidator((tenant_id, dedup_key))
3. 写 event 表（唯一冲突=重复事件，跳过）→ XADD ea:events
4. 消费组 ea:consumer 读取 → 重建租户上下文 → 匹配 campaign.trigger_rule（启用 + RUNNING + 事件类型 + 冷却窗；冷却窗 = 消费端 SETNX `ea:cd:{tenant}:{campaign}:{customerId}` TTL=cooldown，见 3.5）
5. 命中 → 灰度过滤（gray_ratio 抽样，命中写 delivery.gray_hit，E）→ AB 确定性分桶（campaign.ab_mode=AB 时：SHA256 取模 100 定组，变体配置覆盖，写 delivery.ab_group，3.5.1）→ sendTouch（同客户同任务由冷却窗 + 调度去重抑制）
6. XACK；失败消息 → ea:events:dlq + 告警（人工/自动化处置）
```

### 10.4 任务暂停 / 恢复

```
pauseCampaign(Action)：状态校验(RUNNING|SCHEDULED) → PAUSED → 调度器/cron 跳过 + 队列中该任务消息标记暂停
resumeCampaign：PAUSED → 回原状态（SCHEDULED 或 RUNNING）；调度器按 schedule 继续
```

---

## 11. 配置与部署

### 11.1 关键配置（application.yml 要点）

```yaml
spring:
  datasource:
    url: ${DB_URL}            # jdbc:postgresql://pg:5432/eaagent
    username: ${DB_USER}
    password: ${DB_PASS}
  data:
    redis:
      url: ${REDIS_URL}
  flyway:
    enabled: true
ea:
  tenant:
    header: X-Tenant-Id
    context-key: ea:tenant:ctx
  queue:
    events: ea:events
    touch: ea:touch:{tenant}
    dlq: ea:events:dlq
  crypto:
    kms-provider: env          # env|aws|vault
    master-key-id: ${KMS_KEY_ID}
  agent:
    session-ttl: 86400
    approval-timeout-ms: 600000
    max-tool-rounds: 20
    summary-threshold-rounds: 20
  security:
    mfa:
      enabled-endpoints: [channel.write, agent.approve, tenant.manage]  # 9.1
      code-ttl-ms: 60000
      token-ttl-ms: 300000
      max-attempts: 5                        # 5 次/10min 锁定 30min
      delivery-channel: email                # email | sms（走触达管线）
  agentscope:
    model: qwen-max           # 别名，见 ModelRegistry
```

### 11.2 环境变量

| 变量 | 用途 |
|---|---|
| DB_URL / DB_USER / DB_PASS | PostgreSQL |
| REDIS_URL | Redis |
| JWT_SECRET | JWT 签名（≥32B） |
| KMS_KEY_ID / KMS_PROVIDER | 凭据信封主密钥 |
| LLM_API_KEY_DASHSCOPE / OPENAI / ANTHROPIC | agentscope 模型 Key |
| EA_CHANNEL_CONSOLE=on | 联调降级通道开关 |

### 11.3 启动与健康检查

- 启动顺序：Flyway 迁移 → 通道适配器注册 + 配置加载 → ActionRegistry 扫描 → 调度器 → agentscope AgentRunner。
- 端点：`/actuator/health`（存活）、`/actuator/health/readiness`（DB/Redis/队列连通）、`/actuator/metrics`（触达/Agent 指标）。
- K8s：`livenessProbe` 存活、`readinessProbe` 就绪；HPA 按 CPU 与队列长度；配置经 ConfigMap + Secret。

---

## 12. 测试策略

### 12.1 单元测试

- `RuleParserTest`：合法 DSL 解析、非法语法（注入片段）拒绝、Schema 字段白名单校验。
- `ActionPipelineTest`：校验顺序固定、幂等命中返回首次结果、频控/退订/配额异常码。
- `AgentStateMachineTest`：全转移表逐条断言（含非法转移抛 E-15002）。
- `TenantContextTest`：线程池 TaskDecorator 拷贝/清理、缺失上下文拒绝。

### 12.2 集成测试

- **租户隔离矩阵**：租户 A 的 token 查租户 B 对象 → E-12002 / 空结果；跨租户 `(tenant_id, request_id)` 幂等不串号；回执回调跨租户验签失败。
- Action 端到端（console 通道）：sendTouch → 队列 → 发送 → 回执 → delivery 状态流转。
- 审批门控：低权限角色审批拒绝（E-10003）、超时自动拒绝。
- MFA：未携带 `X-MFA-Token` 访问敏感端点拒绝（E-10004）；challenge/verify 全流程 + 校验失败锁定。

### 12.3 AI 质量测试

- 工具选择正确率（回放集）：目标场景下 queryAudience / applyAction 选择准确率 ≥ 95%。
- **越权用例**：prompt 注入「查询租户 2 的客户 c_99」→ 工具拒绝（E-12002）且无数据泄漏。
- 输出 Schema 校验：LLM 给出非法 Action 参数 → E-16002 → 反思重试不超过 2 次。

---

## 13. 参考资料

与《总体架构文档》共用编号体系：

| # | 资料 | 用途 |
|---|---|---|
| [1] | Palantir · Ontology Overview — palantir.com/docs/foundry/ontology/overview/ | 对象/链接/动作官方定义 |
| [2] | Palantir Blog · Building with AIP: Data Tools for RAG/OAG — blog.palantir.com | OAG 理念 |
| [3] | Model Context Protocol — modelcontextprotocol.io | 工具统一接入协议 |
| [4] | Yao et al. · ReAct — arxiv.org/abs/2210.03629 | 推理-行动循环 |
| [5] | Shinn et al. · Reflexion — arxiv.org/abs/2303.11366 | 反思重试范式 |
| [6] | AgentScope Java 2.0 — github.com/agentscope-ai/agentscope-java · java.agentscope.io | Agent 会话事件/权限系统/分布式记忆 |
| [7] | Palantir · AIP Chatbot Studio — palantir.com/docs/foundry/chatbot-studio/overview/ | Agent 最小权限模型 |

---

## 附录 A：演进记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-04 | 详细设计首版：基于《总体架构文档》v1.0 细化 —— 模块/类/接口签名、对象 API 与筛选 DSL、Action 管线 SPI、事件流与调度、状态机转移表、SSE 详细契约、租户上下文实现、通道适配器、13 表 DDL + 索引、安全实现（信封加密/频控/退订/LLM 脱敏）、关键时序、配置与测试策略 |
| v1.1 | 2026-09-04 | Ontology 思想 review 修订（对应架构 v1.1）：3.1 TypeRegistry（schema 驱动 + interfaces 落地，替代封闭枚举）、Audience 双模式（mode + CHECK + 成员互斥）、事件双流定名（EA-Bus / Agent 会话事件）与复盘接线（DeliveryReviewService）、dynamic security 数据级规则（owner_id）、灰度审计（delivery.gray_hit）、对象分类、租户 = Ontology 实例、Function 仅咨询边界 |
| v1.2 | 2026-09-04 | 数据架构 review 加固：业务表间引用全面改复合 FK `REFERENCES x(tenant_id, id)`（跨租户引用 DDL 层拦截）；owner_id / user_id 复合 FK；unsubscribe 定案为平台级全局退订（UNIQUE(customer_key, channel)）；callback_secret 纳入信封加密；新增 CHECK chk_campaign_gray、delivery 回调幂等唯一约束、event/campaign 补索引、分区预建机制；8.4 数据完整性要点 |
| v1.3 | 2026-09-04 | 新增 8.5 Ontology 资产与持久化策略：定义层（TypeRegistry/LinkDef/Action 注册表）为代码工件不入业务库；规则层作为对象属性入库（audience.rule / trigger_rule / frequency_limit）；事件双写（event 表持久 + EA-Bus 传输）；痕迹层（action_log / agent_run / delivery）不可变审计；对照 Palantir Ontology 落地原则（派生链接实时视图、决策痕迹数据集） |
| v1.4 | 2026-09-04 | 缺口补齐（对应数据流 v1.2 / 架构 v1.2）：3.1.1 字段白名单（FieldDef + 7 组字段清单，附 attributes 动态路径与脱敏规则）；9.1 MFA 流程契约（challenge/verify 端点、X-MFA-Token、E-10004、11.1 配置）；9.5 主动退订端点 /api/unsubscribe（含前端入口）；冷却窗 Redis 载体 ea:cd:{tenant}:{campaign}:{customerId}（3.5/10.3）；agent_run 补 tokens_used 计量列（10.2）；SSE 两段式定案（POST 发起 + GET 订阅）；8.3 event 归档表定名 event_archive；15003 赋真实场景（审批未决重提拒绝）；纠错：8.5 行号引用（643/634 → 602/633）、错误码 10xxx 段位表补 10004 |
| v1.5 | 2026-09-04 | AB 实验设计定案（对应数据流 v1.3 / 架构 v1.3）：新增 3.5.1 —— campaign 级 AB（ab_mode/ab_split/ab_variants 三列，不建实验表）、确定性 SHA256 分桶（灰度过滤之后、写 delivery.ab_group）、变体仅策略差异（单变量归因约束）、对照组=主 campaign 配置、ab-report 聚合端点与 Agent 复盘接线；DDL：campaign 插 3 列 + chk_campaign_ab CHECK、delivery 插 ab_group；7.3 前端实验面板；行号引用精修：8.5 规则层（675 / 709 / 739） |
| v1.6 | 2026-09-04 | 登记技术栈设计文档（ea-agent-tech-stack.md v0.1，工程基线：版本选型 / 依赖清单 / 装配 / 配置骨架 / 联调启动，对应架构 v1.4）；1.3 全局约定与技术栈文档对齐（模块划分、多租户禁用租户插件的实现落点）；5.3 读写双闸 / 5.5 边界表租户过滤实现措辞对齐（应用层显式 tenant_id 条件 + 复合 FK 兜底，不用租户插件重写 SQL，与 8.4 一致） |

## 附录 B：错误码表

| code | 含义 | 场景 |
|---|---|---|
| 0 | 成功 | — |
| 10001 | 参数错误 | 校验失败 |
| 10002 | 未认证 | token 缺失/失效 |
| 10003 | 无权限 | RBAC 拒绝 |
| 10004 | MFA 校验失败 | 敏感端点缺 `X-MFA-Token` / token 失效 / 校验超限锁定（9.1） |
| 11001 | 租户上下文缺失 | 未注入 X-Tenant-Id 且不可解析 |
| 11002 | 租户不匹配 | 资源归属 ≠ 上下文 |
| 11003 | 租户已停用 | DISABLED |
| 12001 | 对象不存在 | 查询无命中 |
| 12002 | 对象归属校验失败 | 跨租户引用/遍历拦截 |
| 12003 | DSL 解析失败 | 筛选语法/字段非法 |
| 12004 | 对象类型未知 | TypeRegistry 未注册 / 未知类型 |
| 12005 | 动态安全越权 | 非 owner 操作 audience / campaign（仅 REVIEWER/ADMIN） |
| 12006 | 人群模式写成员拒绝 | mode=DYNAMIC 人群不允许导入成员 |
| 13001 | Action 未注册 | 未知 action 名 |
| 13002 | Action 校验失败 | 业务规则不满足 |
| 13003 | 幂等冲突 | 重复 request_id |
| 13004 | 频控超限 | max_per_day 等 |
| 13005 | 退订拒绝 | 租户/全局退订命中 |
| 13006 | 配额超限 | 租户配额 |
| 13007 | 时段限制 | quiet_hours |
| 14001 | 通道未配置 | 无 channel_config 或禁用 |
| 14002 | 通道不可用 | 熔断/降级 |
| 14003 | 发送失败 | adapter 返回失败 |
| 14004 | 回执验签失败 | HMAC 不符 |
| 15001 | 会话不存在 | sessionId 无效/过期 |
| 15002 | 会话状态不允许 | 非法状态转移 |
| 15003 | 待审批 | 同 `runId` 已有未决审批时再次提交审批请求（幂等重提拒绝） |
| 16001 | LLM 调用失败 | 模型不可用/超时 |
| 16002 | 输出校验失败 | 参数 Schema 不符 |
| 17001 | Function 未注册 | 未知函数名 |

## 附录 C：映射表（对象 ↔ 表 ↔ DTO ↔ API）

| 对象类型 | 表 | Entity/DTO | REST 路径 |
|---|---|---|---|
| customer | customer | CustomerEntity / CustomerDto | /api/customers, /api/objects/customer |
| audience | audience (+ audience_member) | AudienceEntity / AudienceDto | /api/audiences, /api/objects/audience |
| campaign | campaign | CampaignEntity / CampaignDto | /api/campaigns, /api/objects/campaign |
| template | template | TemplateEntity / TemplateDto | /api/templates, /api/objects/template |
| channel | channel_config | ChannelConfigEntity / ChannelConfigDto | /api/channels, /api/objects/channel |
| delivery | delivery | DeliveryEntity / DeliveryDto | /api/deliveries, /api/objects/delivery |
| event | event | EventEntity / EventDto | /api/events, /api/objects/event |
| — | agent_run / action_log / unsubscribe / tenant / tenant_user | 非对象（内部/平台表） | /api/agent/runs, /api/action-log, /api/tenant/* |

---

> 一句话总结：**详细设计把总体架构落到可编码层面 —— 对象 API 与筛选 DSL 是 LLM 与业务世界的唯一边界，Action 管线是写操作的唯一闸门，租户上下文全链路强制，AI 只消费脱敏对象视图、只能经授权工具行动。**