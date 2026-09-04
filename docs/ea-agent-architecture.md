# 基于 Ontology 底座的 AI-Agent 智能运营系统 · 总体架构文档

> **EA-Agent · 多通道运营触达智能体（SaaS 多租户）**
> 版本：v1.4 · 类型：总体架构文档 · 状态：设计定稿
> 技术栈：Vue3 + TypeScript + Element Plus ｜ Spring Boot + MyBatis-Plus + PostgreSQL + Redis + agentscope-java-2.0 + Lombok

---

## 1. 引言

### 1.1 文档目的与范围

本文档定义「基于 Ontology 底座的 AI-Agent 智能运营系统」（EA-Agent）的总体架构：业务目标、分层架构、核心组件设计、数据架构、安全与多租户机制、部署运维与非功能需求。读者：架构师、后端/前端工程师、AI 应用工程师、运维。

### 1.2 术语表

| 术语 | 含义 |
|---|---|
| Ontology | 业务世界模型：把分散数据统一为对象、属性、链接 + 动作的运营层 [1] |
| OAG | Ontology-Augmented Generation：以 Ontology 数据/逻辑/动作锚定 LLM 的决策型生成范式 [2] |
| Action | 写操作唯一入口：内置鉴权、业务校验、幂等、审计的副作用封装 |
| Agent | 自主规划、推理、工具调用、反思的 AI 执行体（agentscope-java-2.0） |
| MCP | Model Context Protocol：AI 应用连接外部数据源/工具/工作流的开放协议 [3] |
| Delivery | 触达记录：每次触达执行结果与回执 |
| 租户 Tenant | SaaS 中独立运营空间（企业客户） |

### 1.3 架构目标与质量属性

| 维度 | 目标 |
|---|---|
| 对象化 | 全业务以「对象-属性-链接」建模，唯一数据入口 = 统一对象 API，杜绝裸 SQL |
| 触达闭环 | 事件驱动 + 异步执行，多通道（email / sms / wechat / push）全链路可观测 |
| 决策演进 | 规则决策 → AI 决策，从「建议者」到「执行者」 |
| 多租户 | SaaS 形态：数据/配置/资源按租户强制隔离，可升级、可计量 |
| 安全 | 纵深防御：租户隔离、Action 权限闸门、LLM 最小权限、合规约束 |

---

## 2. 业务背景与设计理念

### 2.1 背景与痛点

运营侧需通过多渠道自动触达客户（通知 / 营销 / 流失召回）。传统实现为硬编码规则 + 通道 SDK 直连，存在四类问题：

- **规则僵化**：触达策略写死，改策略要发版
- **通道耦合**：各家通道 SDK 散落业务代码，切换/新增成本高
- **权限分散**：谁能对谁发什么，缺少统一闸门
- **无 AI 决策**：系统只能执行规则，无法感知业务状态做判断

### 2.2 设计理念（Ontology 底座 + OAG）

以 **Ontology（业务世界模型）为底座**，AI-Agent 在其上推理、决策、行动：

- **Ontology = 骨架与语言**：统一为业务对象（客户、人群、订单），提供结构化、带状态、实时的「数字孪生」世界
- **Action = 手脚与安全边界**：写操作封装，内置权限校验（RBAC / CBAC），AI 只能在授权范围内行动
- **AI-Agent = 大脑**：理解任务、规划、推理、反思，驱动 Action 完成闭环

官方定义：Palantir 将 Ontology 定位为组织「运营层」（operational layer），位于数据集、虚拟表与模型之上，由**语义元素**（objects / properties / links）与**动力元素**（actions / functions / dynamic security）构成 [1]。OAG 是比 RAG 更广、以决策为中心的版本：LLM 利用确定性逻辑工具与 Action 经 Ontology 与源系统闭环，锚定运营现实、显著降低幻觉 [2]。

---

## 3. 总体架构

### 3.1 分层架构视图

```
┌──────────────────────────────────────────────────────────────┐
│ 应用层 · Vue3 + TS + Element Plus                             │
│ 客户/人群管理 │ 任务编排 │ 通道配置 │ 触达监控 │ Agent 对话工作台 │
└─────────────────────────────┬────────────────────────────────┘
                              │ REST / SSE
┌─────────────────────────────▼────────────────────────────────┐
│ AI-Agent 层 · agentscope-java-2.0                            │
│ Planner │ Reasoning(LLM 循环) │ Memory │ Tool │ MCP │ Reflection│
└─────────────────────────────┬────────────────────────────────┘
                              │ 对象 API / Action 调用（服务端注入租户）
┌─────────────────────────────▼────────────────────────────────┐
│ Ontology 底座（运营层）· Spring Boot + MyBatis-Plus          │
│ 语义层：对象 / 属性 / 链接 / 事件，统一对象 API                │
│ 动力层：触达引擎 · EA-Bus · 异步队列 · 调度                    │
│ 操作层：Actions（发送 / 建任务 / 更新状态 / 导入事件）          │
└──────────────┬──────────────────────────────┬────────────────┘
               │                              │
      ┌────────▼────────┐            ┌────────▼─────────┐
      │ PostgreSQL      │            │ Redis            │
      │ 对象持久化 · 审计 │            │ 事件队列 · 缓存 · 锁│
      └─────────────────┘            └──────────────────┘
```

> 注：系统为 **SaaS 多租户形态**，三层均运行于租户隔离上下文（识别 / 贯穿 / 强制 / 校验，见 7 节）。

### 3.2 分层职责与边界

| 层 | 职责 | 禁止 |
|---|---|---|
| 应用层 | 可视化管理、监控、Agent 对话交互 | 直连数据库、落业务逻辑 |
| AI-Agent 层 | 规划、推理、工具选择、反思；输出决策与动作请求 | 直接执行触达、绕过 Action |
| Ontology 底座 | 对象模型、状态查询、Action 执行与校验、事件与调度 | 写死业务决策 |
| 数据层 | PostgreSQL 持久化 + Redis 队列/缓存 | 对上层暴露裸表 |

### 3.3 关键架构决策（ADR）

| # | 决策 | 理由 | 代价/缓解 |
|---|---|---|---|
| ADR-1 | 多租户默认共享库 + 行级隔离（tenant_id） | SaaS 成本最低、运维最简单 | 大租户可升级 schema/独立库（数据按 tenant_id 迁移） |
| ADR-2 | 事件驱动 + 异步触达（Redis 队列） | 触达执行解耦、抗峰值 | 增加最终一致性处理（幂等 + 回执补偿） |
| ADR-3 | AI 不直连数据：唯一入口 = 对象 API + Action | 权限闸门统一、LLM 无法越权 | Action 开发成本高于裸 SQL |
| ADR-4 | Agent 权限 = 发起用户权限下放，高危转人工 | 最小权限、可审计 | 建议模式降低自动化率（可灰度放开） |
| ADR-5 | 工具统一经 MCP 协议接入 | 通道/数据源解耦，生态复用 | 协议适配层开销 |
| ADR-6 | 租户上下文服务端注入，LLM 不可提供 | LLM 输出不可信，防跨租户越权 | 工具实现必须感知租户上下文 |
| ADR-7 | Agent 输出 SSE 流式（对接 31 类 Agent 会话事件） | 实时渲染、人在环审批体验 | 需前后端事件协议约定 |
| ADR-8 | AB 实验内嵌 campaign（ab_mode/ab_split/ab_variants 三列，不建实验表）+ SHA256 确定性分桶，CONTROL = 主配置 | 与灰度同层：实验是任务编排属性而非独立实体，无新增表/Redis key；分桶可重现（重试/复盘组稳定），单变量归因可解释 | 变体仅限策略差异（channel/template/frequency_limit/gray_ratio），人群与触发固定主 campaign——多变量同改不可归因（3.5.1） |

---

## 4. Ontology 底座（运营层）架构

### 4.1 语义层：对象模型与统一对象 API

**对象模型**（对象 / 属性 / 链接）：

先分类（F）：**业务实体** = Customer（Ontology 本义，LLM 感知世界的主要对象）；**行为对象** = Campaign / Delivery（操作的实例与痕迹）；**配置对象** = Template / Channel；**派生集合** = Audience（见下）。Agent 推理以业务实体网络为中心，行为/配置对象仅作操作上下文。

| 对象 | 含义 | 关键属性 | 关键链接 |
|---|---|---|---|
| Customer 客户 | 业务实体 · 触达基本单元 | 手机、邮箱、微信 openid、标签、画像 jsonb | ∈ Audience |
| Audience 人群 | 派生集合：客户分组 | **模式 DYNAMIC（规则派生）/ STATIC（成员表）**、规则、成员数量、状态 | Customer ∈ Audience（派生链接） |
| Campaign 触达任务 | 一次有目标的触达活动 | 目标人群、渠道、模板、时间、灰度、状态 | → Audience / Channel / Template |
| Template 模板 | 各渠道触达内容 | 渠道、标题/正文/变量、审核状态 | → Channel |
| Channel 通道 | 触达通道 | 类型、凭据引用、启用状态、频控配置 | → 凭据 |
| Delivery 触达记录 | 每次触达执行结果 | 客户、任务、渠道、消息 ID、状态、回执、重试 | → Customer / Campaign |
| Event 事件 | 业务信号，驱动触达 | 事件类型、客户维度、载荷、时间 | → Customer |

**对象接口（多态）**：借鉴 Ontology Interfaces [1] —— 定义「可触达对象」接口（具备 phone / email / wechat_openid 之一），客户、会员、线索等对象统一实现，触达 Action 面向接口而非具体类型。落地：类型注册表携带 interfaces 元数据（详细设计 3.1 TypeRegistry），`sendTouch` 前置校验目标类型 implements `touchable`；v1 内置 customer，会员/线索经注册表扩展。

**统一对象 API 规范**（全系统唯一数据入口，禁止业务代码直写裸 SQL）：

| 项 | 约定 |
|---|---|
| 路由 | `GET/POST /api/objects/{type}`；`GET /api/objects/{type}/{id}` |
| 筛选 | 业务语义过滤条件（等价于人群规则 DSL），非 SQL 片段 |
| 分页 | cursor 分页（`page_token` / `limit`），快照一致 |
| 错误 | 统一错误结构：`{code, message, request_id}`；租户缺失 = 401/拒绝 |
| 权限 | RBAC + 行级租户过滤（服务端强制） |

### 4.2 动力层：事件、异步与调度

- **事件驱动**：`importEvents` 接收业务事件 → 业务事件总线（EA-Bus，Redis Stream）→ 消费端匹配触达任务 → 触发 Action；接收即返回，执行解耦
- **异步执行**：触达发送入异步队列，任务提交即返回；结果落 Delivery，支持重试与回执回调（Webhook）
- **调度**：定时/周期任务（每日提醒、周期性营销）由调度器扫描触发
- **幂等**：事件与触达均带 `dedup_key` / 请求 ID，`(tenant_id, dedup_key)` 唯一，重放安全

### 4.3 操作层：Action 框架

**Action 注册表**（对应 Ontology 动力元素：action types + functions [1]）：

| Action | 入参 | 内置校验 | 副作用 |
|---|---|---|---|
| sendTouch | customer / channel / template / 请求ID | 权限、频控、退订、时段、地址完备 | 异步触达 → Delivery |
| createCampaign | 人群 / 渠道 / 模板 / 时间 / 灰度 | 权限、模板审核状态 | 新建任务 |
| pauseCampaign | campaign | 权限 | 暂停任务 |
| updateCustomerState | customer / 状态 | 权限 | 更新画像状态 |
| importEvents | events[] | 权限、幂等去重 | 入业务事件总线（EA-Bus） |

**Action 执行管线**（统一模板，顺序不可跳过）：

```
鉴权(RBAC/CBAC) → 租户校验(上下文一致) → 幂等(dedup_key)
→ 业务规则(频控/退订/时段/预算) → 配额(租户限流) → 副作用 → 全量审计
```

1. **鉴权**：谁能对谁执行（AI 视同低权限角色，需显式授权）
2. **业务校验**：频控 / 退订 / 时段 / 预算强约束，action 内强制
3. **幂等**：请求 ID 去重，重放不产生重复触达
4. **配额**：租户级触达量/费用上限
5. **审计**：每次调用全量落 `action_log`
6. **回写**：必要时经 Webhook 同步外部系统

### 4.4 安全模型

- **租户隔离**：行级隔离，外部调用强制携带租户上下文，缺失即拒绝（全链路机制见 7 节）
- **Action 权限**：一切写操作走 Action 鉴权，AI 不可绕过
- **凭据保护**：通道凭据加密存储（租户级密钥信封）、展示脱敏
- **合规边界**：频控、退订、时段限制强制生效
- **对象实例级授权（dynamic security）**：租户内按对象归属动态放行 —— audience / campaign 的 owner 数据级规则（7.3）；高危动作审批门控（5.4）

---

## 5. AI-Agent 层架构

### 5.1 模块架构（六大核心模块）

| 模块 | 职责 | 本项目实现 |
|---|---|---|
| **Planner 规划器** | 目标拆解为分步任务清单 | 「召回流失客户」→ 查人群 → 选渠道/话术 → 触达 → 复盘 |
| **Reasoning 推理** | Thinking → Action → Observation 循环 | LLM 核心循环：决定下一步、选工具 |
| **Memory 记忆** | 长期（偏好/角色）/ 短期（会话）/ 工作（步骤） | 长期：运营偏好与画像；短期：会话上下文；工作：中间结果（Redis） |
| **Tool 工具集** | 与外部世界交互 | 对象 API 查询 / 触达 Action / 统计函数 |
| **MCP 连接协议** | 统一工具接入，不硬编码 [3] | PostgreSQL / Redis / 触达通道 / 外部系统 |
| **Reflection 反思** | 自检结果、修正重试 | 触达回执校验、失败重试，反馈推理循环并更新 Memory |

理论依据：推理-行动循环源自 **ReAct**（Yao et al., 2022）[4]；自我反思重试源自 **Reflexion**（Shinn et al., 2023）[5]。

### 5.2 工具集与 MCP

对应 Query Objects / Apply Action / Call Function 三件套：

| 类别 | 工具 | 作用 |
|---|---|---|
| Query Objects | queryCustomers / queryAudience / getCampaign / queryDelivery / queryEvents | 按条件查询对象与状态 |
| Apply Action | `applyAction`（action / args，委托 ActionRegistry：sendTouch / createCampaign / pauseCampaign / updateCustomerState / importEvents，与 4.3 注册表一致） | 写操作收敛为单一工具，Action 由参数选择（详细设计 4.2 定案） |
| Call Function | audienceStats / frequencyCheck / channelPreference / churnRiskScore | 人群统计、频控判断、渠道偏好、流失预测 |

**工具契约**：名称 / 自然语言描述（供 LLM 选择）/ 参数 JSON Schema / 返回结构；租户参数由服务端注入（ADR-6）。Function（audienceStats / frequencyCheck 等）仅作决策咨询；强制约束（频控 / 退订 / 时段）一律在 Action 管线执行，不依赖 LLM 自觉调用。

### 5.3 运行链路与会话状态机

**场景：高价值客户流失召回**

1. 感知：Query Objects 查客户近 30 天活跃度、订单、触达历史（OAG）
2. 推理：LLM 分析流失风险，选定渠道与话术（channelPreference / churnRiskScore）
3. 决策：生成触达方案 —— 人群 × 渠道 × 模板 × 时段
4. 执行：Apply Action `applyAction(sendTouch, …)`，频控 / 退订 / 权限自动校验；异步发送落 Delivery（Action 管线，详细设计 4.4）
5. 观测：Reflection 校验回执（失败重试），更新 Memory

**会话状态机**（每轮 Agent 任务）：

```
new → planning → awaiting_approval ──审批通过──→ executing → observing
   ↘──────────────(建议模式: 直接产出方案)──────────────↘
                                              ↓
                      completed / failed / cancelled（全部落 agent_run 审计）
```

- `awaiting_approval`：高危/超阈值动作（全量触达、删除）或建议模式下的人工确认点
- `executing`：工具调用受限范围（权限系统 deny/allow 精确控制）

### 5.4 人机协同与权限门控

- **建议模式（默认）**：Agent 出方案与话术，人工确认后执行 —— 决策透明、可审计
- **自动模式（显式授权）**：仅在灰度范围内自动执行；超阈值/高危动作强制转人工
- **审计**：`agent_run` 记录目标、规划、决策理由与每次 Action 调用，全程可回放

平台支撑：AgentScope Java 2.0 原生**工具权限系统**（allow / require approval / deny）与 31 类 Agent 会话事件流（实时前端渲染 + 人在环）[6] —— 映射关系：建议模式 = require approval；自动模式 = allow（限定工具+灰度）。对照 Palantir AIP Chatbot Studio（原 Agent Studio）：LLM + Ontology + 文档 + 自定义工具，平台只授予任务所需最小权限 [7]。

### 5.5 记忆与上下文管理

| 层 | 存储 | 生命周期 |
|---|---|---|
| 长期记忆 | PostgreSQL（画像、运营偏好） | 跨会话 |
| 短期记忆 | Redis（会话上下文） | 会话级，TTL |
| 工作记忆 | Redis（本轮步骤与中间结果） | 任务级，失败回收 |

上下文压缩：会话超长时摘要旧轮次，控制 token 成本（agent_run 保留全量原文供审计）。

---

## 6. 应用层架构

### 6.1 前端架构（Vue3 + TS + Element Plus）

| 页面模块 | 能力 |
|---|---|
| 客户/人群管理 | 客户画像查看、人群规则筛选、成员预览 |
| 触达任务编排 | 画布式配置：人群 × 渠道 × 模板 × 时间 × 灰度 |
| 通道配置 | 渠道凭据录入 / 启停 / 连通性测试（脱敏展示） |
| 触达监控 | 实时发送量、到达率、回执状态、失败明细 |
| Agent 对话工作台 | 对话 + 智能卡片，任务审批（建议模式入口） |

### 6.2 API 设计规范

| 路由 | 说明 |
|---|---|
| `/api/objects/*` | 统一对象查询（读） |
| `/api/campaigns` | 任务 CRUD |
| `/api/actions/*` | Action 执行（写，含校验与审计） |
| `/api/channels` | 通道配置 |
| `/api/agent/chat` | Agent 对话（SSE 流式） |
| `/api/tenant/*` | 租户管理、配额与计量（平台侧） |

通用约定：REST + 统一错误结构；写操作幂等（请求 ID）；租户上下文经网关注入 `X-Tenant-Id`。

### 6.3 SSE 事件协议（Agent 对话）

对接 AgentScope 31 类 Agent 会话事件，`/api/agent/chat` 以 `text/event-stream` 输出：

```
event: plan            data: {"steps": ["查人群", "分析风险", "发送触达", "复盘"]}
event: thinking_delta  data: {"delta": "客户近30天无下单..."}
event: tool_call       data: {"tool": "queryCustomers", "status": "start"}
event: approval_required data: {"action": "sendTouch", "scope": "全量", "requires": "manager"}
event: action_result   data: {"action": "sendTouch", "status": "accepted", "delivery_id": "..."}
event: text_delta      data: {"delta": "已触达 12,400 人"}
event: done            data: {"agent_run_id": "..."}
```

前端按事件类型渲染：智能卡片（表格/图表/链接）+ 自然语言流式文本 + 审批按钮。

---

## 7. SaaS 多租户设计

多租户是横切关注点：数据、配置、Agent 会话、计量四层按租户隔离。原则：**默认共享、强制隔离、可升级**。

**第一条原则：每个租户 = 一个独立 Ontology 实例** —— 对象集、链接、Action 权限、dynamic security 均在其内。这解释了为何租户过滤必须在数据访问与工具执行双层强制（ADR-1 / ADR-6）。

### 7.1 租户模型与隔离策略

| 层级 | 角色 | 说明 |
|---|---|---|
| 平台 Platform | SaaS 运营方 | 管理租户、套餐、全局合规（退订总表、监管） |
| 租户组织 Tenant | 客户企业 | 客户/人群/任务/模板/通道/触达记录独立空间 |
| 成员用户 User | 租户内使用者 | RBAC（运营 / 管理员 / 审核员），决定 Agent 权限上限 |

| 隔离模式 | 说明 | 适用 |
|---|---|---|
| 共享库 + 行级隔离（默认） | 全表 `tenant_id`，查询强制过滤 | 全量租户 |
| Schema 级隔离 | 每租户独立 schema | 大租户（付费升级） |
| 独立库/实例 | 物理隔离 | 金融/政务合规 |

### 7.2 租户上下文链路（不可绕过的不变式）

1. **识别**：子域名/自定义域名 + 登录态声明，网关校验后注入 `X-Tenant-Id`
2. **贯穿**：HTTP → 服务层 → 数据访问 → Agent 会话 → 工具调用，全链路携带
3. **强制**：数据访问层统一注入租户过滤（应用层显式 `tenant_id` 条件，业务代码不可绕过；DDL 复合 FK 兜底，见 8.4 / ADR-1——不使用租户插件重写 SQL），缺失即拒绝
4. **校验**：对象 id 访问先验归属（`tenant_id` 匹配）

### 7.3 Agent 层租户安全（AI × SaaS 关键差异）

LLM 输出不可信，**隔离不能依赖 prompt，必须执行层强制**：

- **会话绑定租户**：每个 Agent 会话（sessionId / userId）绑定唯一租户；LLM 只接触本租户语义与数据
- **工具执行强制过滤**：所有工具实现内注入当前租户过滤 —— 租户参数服务端注入，**不由 LLM 提供**
- **对象归属校验**：LLM 生成的 customer_id / campaign_id 必须属于当前租户，否则工具调用拒绝
- **权限继承**：Agent 的 Action 权限 ≤ 发起用户角色权限；高危动作按 5.4 转人工审批
- **配置隔离**：模板库、话术风格、通道凭据、频控策略按租户命名空间读取，不跨租户共享
- **数据级授权（dynamic security）**：audience / campaign 携带 owner_id，规则：非 owner 仅 REVIEWER / ADMIN 可操作 —— 租户内再按业务条件收敛权限（含 Agent 下放）

### 7.4 计量、配额与计费

| 维度 | 指标 | 控制点 |
|---|---|---|
| 触达 | 发送量 / 到达率 / 通道 | 租户级频控与预算 |
| AI | 会话数 / token 用量 / 工具调用 | 并发会话上限、AI 额度 |
| 存储 | 客户画像 / 事件量 | 容量计费 |

超额策略：阈值告警 → 限流 → 拒绝（配合异步队列背压）。

---

## 8. 数据架构

### 8.1 领域模型关系

客户 ∈ 人群（多对多，成员快照可选）；触达任务 → 人群 + 渠道 + 模板；触达记录 → 客户 + 任务；事件 → 客户（可选）；全部实体归属租户。

### 8.2 物理模型草案（PostgreSQL）

```sql
tenant          (id, name, plan, status, quota jsonb, created_at)
tenant_user     (id, tenant_id, login_name, name, role, status, created_at)

customer        (id, tenant_id, external_id, phone, email, wechat_openid,
                 attributes jsonb, status, created_at, updated_at)
audience        (id, tenant_id, name, mode, rule, owner_id, status, created_at)
                -- mode: DYNAMIC(规则派生)|STATIC(成员表)；rule 与成员互斥
audience_member (tenant_id, audience_id, customer_id, created_at)

campaign        (id, tenant_id, name, audience_id, channel, template_id,
                 schedule, gray_ratio, owner_id, status, ...)
template        (id, tenant_id, channel, title, content, vars jsonb, status,
                 review_status)
channel_config  (id, tenant_id, channel, config_encrypted, enabled,
                 frequency_limit jsonb)

delivery        (id, tenant_id, campaign_id, customer_id, channel,
                 channel_msg_id, gray_hit, status, error, attempt,
                 created_at, updated_at)      -- gray_hit: 灰度抽样命中审计
event           (id, tenant_id, customer_id, event_type, payload jsonb,
                 dedup_key, created_at)

agent_run       (id, tenant_id, session_id, user_id, goal, plan jsonb,
                 decisions jsonb, status, created_at)          -- AI 审计
action_log      (id, tenant_id, actor_type, actor_id, action, args jsonb,
                 result, request_id, created_at)               -- Action 审计
```

### 8.3 索引与唯一约束策略

- 全部主表：`(tenant_id, id)` 复合主键或 `tenant_id` 前缀复合索引
- `(tenant_id, dedup_key)` 唯一：事件/触达跨租户防重放
- `delivery(tenant_id, campaign_id, created_at)`：任务维度监控查询
- `event(tenant_id, event_type, created_at)`：业务事件消费查询
- 画像字段 jsonb 采用 GIN 索引（标签检索）

### 8.4 数据生命周期

- Delivery 明细：热数据保留 N 个月 → 归档分区表 → 冷存
- Event 原始载荷：保留窗口期（幂等与审计需要）→ 归档
- agent_run / action_log：审计数据长期保留（合规），分表/分区
- 退订状态：永久保留，跨租户全局退订总表（平台级）

---

## 9. 技术架构

### 9.1 技术选型与依据

| 层 | 技术 | 角色与依据 |
|---|---|---|
| 前端 | Vue3 + TypeScript + Element Plus | 组件化、生态成熟 |
| 后端 | Spring Boot + Lombok | 服务编排 |
| 数据访问 | MyBatis-Plus + PostgreSQL | 复合 FK 跨租户拦截 + 应用层 TenantContext（不用租户插件，8.4）、jsonb 画像 |
| 队列/缓存 | Redis | 事件队列 / 异步任务 / 分布式锁 / 缓存 |
| AI | agentscope-java-2.0（`io.agentscope:agentscope-harness`，JDK 17+） | ReActAgent/HarnessAgent、31 类 Agent 会话事件、权限系统、Workspace 沙箱、分布式会话与记忆（Redis/PG）、多智能体编排、模型扩展（OpenAI/Anthropic/DashScope/Ollama）[6] |
| 构建 | Maven 多模块 | 分层隔离、独立演进 |

### 9.2 代码模块划分（Maven）

```
ea-common      租户上下文、错误码、幂等、安全工具（无业务依赖）
ea-api         REST 接口层、DTO、SSE 协议定义
ea-ontology    对象模型、Action 框架与注册表、对象 API 实现、事件与调度
ea-agent       agentscope 集成：工具实现、会话状态机、记忆管理、审批门控
ea-channel     通道适配器（sms / email / wechat / push），回执回调
ea-app         启动装配、网关过滤器、租户解析
```

### 9.3 关键时序

**发送触达（异步闭环）**

```
外部系统 → 网关(租户解析) → 对象API → ActionService.sendTouch
  → 校验管线(鉴权/幂等/频控/配额) → 异步队列(消费端：灰度抽样 + AB 确定性分桶，3.5.1) → 触达执行器
  → 通道适配器 → 外部通道 → 回执回调 → Delivery 更新 → EA-Bus 事件(通知/复盘)
```

**Agent 对话（SSE 流式）**

```
前端 → /api/agent/chat(SSE) → AgentService(session=租户绑定)
  → Reasoning 循环 → 工具(MCP) → 对象API/Action(服务端注入租户)
  → Agent 会话事件(thinking/tool_call/approval_required/text_delta) → SSE 回传
  → done(agent_run_id) → 前端落智能卡片与审计
```

---

## 10. 部署与运维架构

### 10.1 部署拓扑（单机 → 云原生演进）

- **单机起步**：应用单体 + PostgreSQL + Redis 一台/一组，支撑早期租户
- **云原生演进**：无状态服务水平扩展（K8s Deployment + HPA）；Redis Cluster、PG 主从/高可用；Agent 工具运行于 Workspace 沙箱（Docker/K8s），防工具代码越权
- **多区域（可选）**：通道网关就近出口，回执经事件总线聚合

### 10.2 可观测性

| 维度 | 内容 |
|---|---|
| 日志 | 结构化（request_id / tenant_id / agent_run_id），检索与排障 |
| 指标 | 发送量、到达率、回执分布、Agent 会话成功率、token 用量、Action 失败率 |
| 审计 | agent_run / action_log 不可变留存，租户与管理后台可回放 |
| 告警 | 触达失败率、队列积压、配额超限、跨租户校验失败（安全事件） |

### 10.3 配置与密钥管理

- 集中配置（环境维度 profile），敏感项不入代码库
- 通道凭据加密存储（租户级密钥信封），展示脱敏、导出受限
- LLM API Key 平台级托管（agentscope ModelRegistry 环境变量约定 [6]）

---

## 11. 非功能需求

| 类别 | 指标 |
|---|---|
| 性能 | 对象 API P95 < 200ms；触达发送提交 P95 < 500ms；Agent SSE 首包 < 2s |
| 吞吐 | 单实例异步触达 ≥ 5,000 条/分钟；队列削峰支持大促突发 |
| 可用性 | SLA 99.9%；通道故障自动降级与重试 |
| 容量 | 初期 100 租户 / 千万级客户画像 / 亿级 Delivery（分区） |
| 安全 | 租户隔离 0 越权（渗透视角）、凭据加密、审计全量 |
| 合规 | 频控、退订（含全局退订）、时段限制强制执行 |

---

## 12. 演进路线

| 阶段 | 内容 | 目标 |
|---|---|---|
| 一：基础设施 | 对象模型 + 统一对象 API + 前端对象管理 + 租户隔离底座 | Ontology 底座可用（含多租户） |
| 二：触达闭环 | 通道接入（email/sms/wechat）、任务编排、业务事件 + 异步队列、触达监控 | 多通道自动化触达跑通 |
| 三：规则决策 | 人群条件筛选、灰度/AB（灰度已定案；AB 已设计：详细设计 3.5.1 + 8.1 DDL）、频控与合规 | 规则驱动决策上线 |
| 四：AI 决策 | agentscope 六模块接入、OAG 工具集、人机协同（建议/自动）、agent_run 审计 | 从规则决策演进到 AI 决策 |
| 五：SaaS 商业化 | 计量计费、租户升级通道（schema/独立库）、渠道扩增、Agent 团队编排 | 多租户商业化闭环 |

---

## 13. 风险与约束

- **LLM 决策不可完全信任** → 默认建议模式；Action 强制业务校验，高危动作转人工
- **重复触达风险** → 幂等去重 + 频控前置，重放安全
- **外部通道不稳定** → 降级策略（无凭据时 console 降级可联调）、失败重试与回执回调
- **数据安全** → 租户隔离强制、凭据加密、Agent 仅经对象 API 与 Action 访问数据
- **跨租户越权（AI 特有风险）** → LLM 输入不可信：工具执行层强制注入租户过滤 + 对象归属校验（7.3），缺租户上下文即拒绝
- **Token 成本** → 记忆压缩、工具调用限额、计量配额
- **合规演进** → 行业监管（短信实名、微信模板审核）需随渠道接入同步适配

---

## 14. 参考资料

| # | 资料 | 用途 |
|---|---|---|
| [1] | Palantir 官方文档 · Ontology Overview — palantir.com/docs/foundry/ontology/overview/ | Ontology 定位、语义/动力元素官方定义 |
| [2] | Palantir Blog · Building with AIP: Data Tools for RAG/OAG（Chad Wahlquist）— blog.palantir.com | OAG 定义与落地（AIP Logic、HyperAuto、OMA） |
| [3] | Model Context Protocol 官方文档 — modelcontextprotocol.io | MCP 协议标准（工具统一接入） |
| [4] | Yao et al. · ReAct: Synergizing Reasoning and Acting in Language Models — arxiv.org/abs/2210.03629 | 推理-行动循环范式 |
| [5] | Shinn et al. · Reflexion: Language Agents with Verbal Reinforcement Learning — arxiv.org/abs/2303.11366 | 自我反思与重试范式 |
| [6] | AgentScope Java 2.0 — github.com/agentscope-ai/agentscope-java · java.agentscope.io | 本项目 AI 框架能力依据（Agent 会话事件/权限系统/分布式记忆） |
| [7] | Palantir 官方文档 · AIP Chatbot Studio（原 Agent Studio）— palantir.com/docs/foundry/chatbot-studio/overview/ | Agent 构建、最小权限模型、Workshop 集成 |

---

## 附录 A：文档演进记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-04 | 总体架构文档首版：基于《基于 Ontology 底座的 AI-Agent 设计文档》（v0.1）整理，补齐 ADR、Action 执行管线、会话状态机、SSE 事件协议、数据架构 DDL、技术架构（模块/时序）、部署运维、NFR、演进路线五阶段、SaaS 多租户章节 |
| v1.1 | 2026-09-04 | Ontology 思想 review 修订：Audience 双模式（DYNAMIC/STATIC）、事件双流定名（EA-Bus / Agent 会话事件）与复盘接线、Interfaces 落地（TypeRegistry）、dynamic security（owner 数据级授权）、灰度审计（gray_hit）、对象分类、租户 = Ontology 实例、Function 仅咨询职责 |
| v1.2 | 2026-09-04 | 与详细设计 v1.4 对齐：5.2 工具机制收敛为单一 `applyAction`（委托 ActionRegistry，5 Action 由参数选择，同步 5.3 运行链路表述）；总体衔接详细设计缺口补齐（MFA 流程契约、主动退订端点、冷却窗 Redis 载体、agent_run token 计量、SSE 两段式）——细节以详细设计 9.1 / 9.5 / 3.5 / 10.2 / 7.4 为准 |
| v1.3 | 2026-09-04 | AB 实验设计定案（ADR-8，详细设计 3.5.1）：实验内嵌 campaign 三列 + SHA256 确定性分桶 + CONTROL=主配置；9.3 发送触达时序补「灰度 + AB 分桶」；12 阶段三标注 AB 已设计 |
| v1.4 | 2026-09-04 | 登记技术栈设计文档（ea-agent-tech-stack.md v0.1，工程基线细化：版本选型 / 依赖 / 装配 / 配置 / 联调）；9.1 数据访问行与 7.2 强制条款措辞修正——多租户实现 = 复合 FK 跨租户拦截 + 应用层 TenantContext（不用租户插件重写 SQL，与 8.4 / ADR-1 一致） |

> 一句话总结：**Ontology 底座提供结构化、带状态、实时的业务世界模型与安全 Action 边界；AI-Agent 在其上规划、推理、反思、执行 —— 让 AI 从「建议者」变为「执行者」，完成从数据、洞察到决策、行动的闭环。**