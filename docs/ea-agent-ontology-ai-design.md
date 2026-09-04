# 基于 Ontology 底座的 AI-Agent 设计文档

> **EA-Agent · 多通道运营触达智能体**
> 状态：设计草案 v0.1
> 技术栈：前端 Vue3 + TypeScript + Element Plus；后端 Spring Boot + MyBatis-Plus + PostgreSQL + Redis + agentscope-java-2.0 + Lombok

---

## 1. 背景与目标

### 1.1 业务背景

运营侧需要通过多渠道（短信 / email / 微信 / App push）自动触达客户：通知、营销、流失召回等。传统实现是硬编码规则 + 各通道 SDK 直连，存在四个问题：

- **规则僵化**：触达策略写死在代码里，改策略要发版
- **通道耦合**：各家通道 SDK 散落业务代码，切换/新增成本高
- **权限分散**：谁能对谁发什么，缺少统一闸门
- **无 AI 决策**：系统只能执行规则，无法感知业务状态做判断

### 1.2 设计蓝图（借鉴 Palantir Foundry / AIP）

以 **Ontology（业务世界模型）为底座**，AI-Agent 在其上推理、决策、行动：

- **Ontology = 骨架与语言**：把分散数据统一为业务对象（客户、订单、人群），提供结构化、带状态、实时的「数字孪生」世界
- **Action = 手脚与安全边界**：写操作（创建/更新/删除）的封装，内置权限校验（RBAC/CBAC），AI 只能在授权范围内行动
- **AI-Agent = 大脑**：理解任务、规划、推理、反思，驱动 Action 完成闭环

**核心理念 OAG（Ontology-Augmented Generation）**：Agent 通过查询业务对象感知状态，而非直接读原始数据库 —— 比传统 RAG 更可靠（结构化、实时、带状态）。

**官方定义佐证**：Palantir 官方文档将 Ontology 定位为组织的「运营层」（operational layer）：位于数据集、虚拟表与模型之上，把数字资产连接到现实对应物，充当组织的**数字孪生**，由**语义元素**（objects / properties / links）与**动力元素**（actions / functions / dynamic security）构成 [1]。OAG 是比 RAG 更广、以**决策为中心**的版本：让 LLM 利用确定性的逻辑工具（预测、优化）与 Action，经 Ontology 与源系统闭环，把 LLM 锚定在企业运营现实中，显著降低幻觉 [2]。

### 1.3 系统目标

| 维度 | 目标 |
|---|---|
| 对象化 | 全业务以「对象-属性-链接」建模，提供统一对象 API，杜绝面向裸 SQL 的业务编码 |
| 触达闭环 | 事件驱动 + 异步执行，支持 email / sms / wechat 多通道，全链路可观测（触达记录 = Delivery） |
| 决策演进 | 规则决策 → AI 决策（agentscope-java-2.0），从「建议者」到「执行者」 |
| 安全 | 租户隔离、Action 权限闸门、凭据加密、合规约束（频控 / 退订 / 时段） |

**KPI（示例）**：触达到达率 ≥ 99%；流失召回唤醒率提升 ≥ 30%；触达任务从配置到执行分钟级；人工介入比例下降 ≥ 50%。

---

## 2. 总体架构

三层架构：**Ontology 底座**（骨架）→ **AI-Agent 层**（大脑）→ **应用层**（界面）。

```
┌──────────────────────────────────────────────────────────────┐
│ 应用层 · Vue3 + TypeScript + Element Plus                     │
│ 客户/人群管理 │ 任务编排 │ 通道配置 │ 触达监控 │ Agent 对话工作台 │
└─────────────────────────────┬────────────────────────────────┘
                              │ REST / SSE
┌─────────────────────────────▼────────────────────────────────┐
│ AI-Agent 层 · agentscope-java-2.0                            │
│ Planner 规划 │ Reasoning(LLM 循环) │ Memory 记忆               │
│ Tool 工具集 │ MCP 统一接入 │ Reflection 反思重试                │
└─────────────────────────────┬────────────────────────────────┘
                              │ 对象 API / Action 调用
┌─────────────────────────────▼────────────────────────────────┐
│ Ontology 底座 · Spring Boot 3 + MyBatis-Plus                 │
│ 语义层：对象 / 属性 / 链接 / 事件，统一对象 API（OAG 语义层）     │
│ 动力层：触达引擎 · EA-Bus · 异步队列 · 调度                    │
│ 操作层：Actions（发送/建任务/更新状态/导入事件），权限与合规封装   │
└──────────────┬──────────────────────────────┬────────────────┘
               │                              │
      ┌────────▼────────┐            ┌────────▼─────────┐
      │ PostgreSQL      │            │ Redis            │
      │ 对象/属性/链接持久化│            │ 事件队列·异步任务·缓存│
      └─────────────────┘            └──────────────────┘
```

**分层职责与边界**：

| 层 | 职责 | 不做 |
|---|---|---|
| 应用层 | 可视化管理、监控、Agent 对话交互 | 不直连库、不落业务逻辑 |
| AI-Agent 层 | 规划、推理、工具选择、反思；输出决策与动作请求 | 不直接执行触达，不绕过 Action |
| Ontology 底座 | 对象模型、状态查询、Action 执行与校验、事件与调度 | 不写死业务决策 |
| 数据层 | PostgreSQL 持久化 + Redis 队列/缓存 | 不对上层暴露裸表 |

> 注：系统为 **SaaS 多租户形态**，上述三层均运行于租户隔离上下文（识别 / 贯穿 / 强制 / 校验，见 6 节）。

---

## 3. Ontology 底座设计（核心）

### 3.1 业务对象模型（Object / Property / Link）

对象分四类（F）：**业务实体** = Customer（Ontology 本义，LLM 感知世界的主要对象）；**行为对象** = Campaign / Delivery；**配置对象** = Template / Channel；**派生集合** = Audience。Agent 推理以业务实体网络为中心。

| 对象 | 含义 | 关键属性 | 关键链接 |
|---|---|---|---|
| Customer 客户 | 触达基本单元 | 手机、邮箱、微信 openid、标签、活跃状态、画像 jsonb | ∈ Audience |
| Audience 人群 | 派生集合 | 模式 DYNAMIC（规则实时派生）/ STATIC（成员表），二选一、规则、成员数量、状态 | Customer ∈ Audience（DYNAMIC 派生链接 / STATIC 成员表） |
| Campaign 触达任务 | 一次有目标的触达活动 | 目标人群、渠道、模板、时间、灰度比例、状态 | → Audience / Channel / Template |
| Template 模板 | 各渠道触达内容 | 渠道、标题/正文/变量、审核状态 | → Channel |
| Channel 通道 | 触达通道 | 类型、凭据引用、启用状态、频控配置 | → 凭据 |
| Delivery 触达记录 | 每次触达执行结果 | 客户、任务、渠道、消息 ID、状态、回执、重试次数 | → Customer / Campaign |
| Event 事件 | 业务信号，驱动触达 | 事件类型、客户维度、载荷、时间 | → Customer（可选） |

关系：客户 ∈ 人群（多对多）；触达任务 → 人群 + 渠道 + 模板；触达记录 → 客户 + 任务。

人群双模式二选一（A）：DYNAMIC 由规则实时派生成员，不落成员表；STATIC 仅成员表、rule 为空；导入成员仅允许 STATIC（详见《详细设计》3.3 / 8.1）。

### 3.2 语义层：统一业务语言（「是什么」）

- **统一对象 API**：`GET/POST /api/objects/{type}` 查询/筛选/分页；全系统只有对象 API 这一种数据入口，禁止业务代码直写裸 SQL。
- **触达语义规则**：规则用业务语义表达（「近 30 天未下单的高价值客户」），不是 SQL 片段 —— 人群筛选条件可被 AI 与运营共用。
- **事件模型**：标准化事件入流（event_type / payload / 关联客户），作为动力层输入。
- **画像**：客户自定义属性 jsonb 存储，支持动态扩展（标签、活跃度、偏好渠道）。
- **对象接口（多态）**：借鉴 Ontology Interfaces —— 具有共同形状的对象类型可统一建模与交互 [1]。本项目可定义「可触达对象」接口（具备 phone / email / wechat_openid 之一），客户、会员、线索等对象统一实现，触达 Action 面向接口而非具体类型。落地：类型注册表携带 interfaces 元数据（TypeRegistry，见《详细设计》3.1），sendTouch 校验目标类型实现 touchable，新增类型无需改枚举。

### 3.3 动力层：事件与异步执行（「发生了什么」）

- **事件驱动**：importEvents 接收业务事件 → 写入业务事件总线（EA-Bus，Redis Stream）→ 消费端异步匹配触达任务 → 触发 Action。事件到达与触达执行解耦，接收即返回。
- **异步执行**：触达发送入异步队列，任务提交即返回；结果落 Delivery，支持重试与回执回调（Webhook）。
- **调度**：定时/周期任务（每日提醒、周期性营销）由调度器扫描触发。
- **幂等**：事件与触达均带去重键（dedup_key / 请求 ID），重放安全。

### 3.4 操作层：Actions（「该怎么做」的执行端）

**Action 注册表（草案）**（对应 Ontology 动力元素：action types + functions [1]）：

| Action | 入参 | 内置校验 | 副作用 |
|---|---|---|---|
| sendTouch | customer / channel / template / 请求ID | 权限、频控、退订、时段、地址完备 | 异步触达 → Delivery |
| createCampaign | 人群 / 渠道 / 模板 / 时间 / 灰度 | 权限、模板审核状态 | 新建任务 |
| pauseCampaign | campaign | 权限 | 暂停任务 |
| updateCustomerState | customer / 状态 | 权限 | 更新画像状态 |
| importEvents | events[] | 权限、幂等去重 | 入业务事件总线（EA-Bus） |

**Action 设计要点**（统一模板）：

1. **鉴权**：RBAC/CBAC —— 谁能对谁执行（AI 视同低权限角色，需显式授权）
2. **业务校验**：频控 / 退订 / 时段 / 预算等强约束，action 内强制
3. **幂等**：请求 ID 去重，重放不产生重复触达
4. **审计**：每次调用全量落审计（谁、何时、什么动作、结果）
5. **回写**：必要时经 Webhook 同步外部系统

### 3.5 安全模型

- **租户隔离**：多租户行级隔离，外部调用强制携带租户上下文，缺失即拒绝（全链路机制见 6 节）
- **Action 权限**：一切写操作走 Action 鉴权，AI 不可绕过
- **凭据保护**：通道凭据 AES 加密存储、展示脱敏
- **合规边界**：频控、退订、时段限制强制生效

---

## 4. AI-Agent 层设计

### 4.1 六大核心模块

| 模块 | 职责 | 本项目实现 |
|---|---|---|
| **Planner 规划器** | 目标拆解为分步任务清单 | 任务编排：把「召回流失客户」拆成 查人群 → 选渠道/话术 → 触达 → 复盘 |
| **Reasoning 推理** | Thinking → Action → Action Input 循环 | LLM 核心循环：决定下一步做什么、选哪个工具 |
| **Memory 记忆** | 长期（偏好/角色）/ 短期（任务上下文）/ 工作（步骤与中间结果） | 长期：运营偏好与画像；短期：会话上下文；工作：本轮步骤与中间结果（Redis） |
| **Tool 工具集** | 与外部世界交互 | 对象 API 查询 / 触达 Action / 统计函数 |
| **MCP 连接协议** | 连接 AI 应用与外部系统的开放标准（类比 AI 的「USB-C 口」）[3]，工具统一经协议接入、不硬编码 | PostgreSQL / Redis / 触达通道 / 外部系统统一接入 |
| **Reflection 反思** | 自检结果、发现错误、修正重试 | 触达回执校验、失败重试，结果反馈推理循环并更新 Memory |

**理论依据**：Reasoning 的「思考 → 行动 → 观察」循环源自 **ReAct 范式**（Yao et al., 2022）[4]；Reflection 的自我反思与重试范式源自 **Reflexion**（Shinn et al., 2023）[5]。

### 4.2 工具集（Tools）定义

对应 Query Objects / Apply Action / Call Function 三件套：

| 类别 | 工具 | 作用 |
|---|---|---|
| Query Objects | queryCustomers | 按条件查客户及画像（Excel 式筛选 + 分页） |
| | queryAudience | 查人群成员与统计 |
| | getCampaign | 查任务状态 |
| | queryDelivery | 查触达记录与回执 |
| | queryEvents | 查业务事件 |
| Apply Action | sendTouch / createCampaign / pauseCampaign / updateCustomerState / importEvents | 与 3.4 注册表一致 |
| Call Function | callFunction（name / args，委托 FunctionRegistry：audienceStats / frequencyCheck / channelPreference / churnRiskScore / bestSendTime） | 人群统计、频控判断、渠道偏好、流失预测、最优发送时段 |

**工具契约**：每个工具固定提供 —— 名称 / 自然语言描述（供 LLM 选择）/ 参数 JSON Schema / 返回结构。Function 经 FunctionRegistry 注册、callFunction 单工具路由（与 ActionRegistry 对称），仅供决策咨询，不做任何强制；强制约束（频控 / 退订 / 时段）一律在 Action 执行（3.4），不依赖 LLM 自觉调用（H）。

### 4.3 运行链路（场景：高价值客户流失召回）

1. **感知**：Query Objects 查客户近 30 天活跃度、订单、触达历史（OAG）
2. **推理**：LLM 分析流失风险，选定渠道与话术（参考 channelPreference / churnRiskScore）
3. **决策**：生成触达方案 —— 人群 × 渠道 × 模板 × 时段
4. **执行**：Apply Action sendTouch，频控 / 退订 / 权限自动校验；异步发送落 Delivery
5. **观测**：Reflection 校验回执（失败重试），更新 Memory，供后续任务复用

交互输出双形态：**智能卡片**（表格 / 图表 / 链接 / 文件）+ **自然语言回复**。

### 4.4 伪代码

```text
goal = "对近30天未下单的高价值客户做流失召回"
plan = Planner.decompose(goal)   # [查人群, 分析风险, 选渠道话术, 发送触达, 复盘]
for step in plan:
    while not done:
        thought = Reasoning.think(step, Memory)            # 思考
        action  = Reasoning.select_tool(thought)           # 选工具（经 MCP）
        result  = MCP.call(action, action_input)           # 调对象API / Action / 函数
        if not Reflection.check(result, thought):          # 自检
            continue                                       # 回推理循环重试
        Memory.work.append(result)                         # 更新工作记忆
    if step.action_is_send:                                # 触达步骤
        ApplyAction.sendTouch(plan.args)                   # 权限/频控/退订内置
Memory.long_term.update(context)
output(FinalAnswer, TaskResult)
```

### 4.5 人机协同与信任边界

- **建议模式（默认）**：Agent 出方案与话术，人工确认后执行 —— 决策透明、可审计
- **自动模式（显式授权）**：仅在灰度范围内自动执行；超阈值 / 高危动作（全量触达、删除）强制转人工
- **审计**：agent_run 记录目标、规划、决策理由与每次 Action 调用，全程可回放

> **平台支撑**：AgentScope Java 2.0 原生提供**工具调用权限系统**（allow / require user approval / deny）与 31 类 Agent 会话事件流（实时前端渲染 + 人机协同）[6]——即本系统的「Agent 会话事件」（与业务事件总线 EA-Bus 相互独立，见 3.3），可直接落地上述「建议模式 = require approval、自动模式 = allow（限定工具与灰度范围）」。对照 Palantir AIP Chatbot Studio（原 Agent Studio）：LLM + Ontology + 文档 + 自定义工具，平台安全模型只授予任务所需的最小权限 [7]。

---

## 5. 应用层设计

| 页面 | 能力 |
|---|---|
| 客户/人群管理 | 客户画像查看、人群规则筛选、成员预览 |
| 触达任务编排 | 画布式配置：人群 × 渠道 × 模板 × 时间 × 灰度 |
| 通道配置 | 各渠道凭据录入 / 启停 / 连通性测试（凭据脱敏展示） |
| 触达监控 | 实时发送量、到达率、回执状态、失败明细 |
| Agent 对话工作台 | 对话 + 智能卡片，任务审批（建议模式）入口 |

**API 概要**：`/api/objects/*`（统一对象查询）、`/api/campaigns`、`/api/actions/*`、`/api/channels`、`/api/agent/chat`（SSE 流式）。

---

## 6. SaaS 多租户设计

多租户是本系统的横切关注点：数据、配置、Agent 会话、计量四层都要按租户隔离。设计原则：**默认共享、强制隔离、可升级**。

**第一条原则（G）：每个租户 = 一个独立 Ontology 实例** —— 对象集、链接、Action 权限、dynamic security 均在其内；这解释了为何租户过滤要在数据访问与工具执行双层强制（6.2 / 6.3）。

### 6.1 租户模型与隔离策略

**租户层级**：

| 层级 | 角色 | 说明 |
|---|---|---|
| 平台 Platform | SaaS 运营方 | 管理租户、套餐、全局合规（退订总表、行业监管） |
| 租户组织 Tenant | 客户企业 | 独立运营空间：客户/人群/任务/模板/通道配置/触达记录全部隔离 |
| 成员用户 User | 租户内使用者 | RBAC 角色（运营 / 管理员 / 审核员），决定 Agent 可继承的权限上限 |

**隔离模式**（SaaS 经典三选一）：

| 模式 | 说明 | 适用 |
|---|---|---|
| 共享库 + 行级隔离（默认） | 所有表带 `tenant_id`，查询强制过滤；成本最低、运维最简单 | 全量租户 |
| Schema 级隔离 | 每租户独立 schema，隔离强、迁移/导出方便 | 大租户（付费升级） |
| 独立库/实例 | 物理隔离最强 | 金融、政务等合规要求 |

推荐路线：**默认共享库行级隔离**，预留升级通道（数据按 `tenant_id` 可导出迁移）。

### 6.2 租户上下文链路（不可绕过的不变式）

1. **识别**：租户经子域名/自定义域名 + 登录态声明解析，网关校验后注入 `X-Tenant-Id`
2. **贯穿**：HTTP 请求 → 服务层 → 数据访问 → Agent 会话 → 工具调用，全链路携带
3. **强制**：数据访问层统一注入租户过滤条件（应用层显式 `tenant_id`，复合 FK 兜底，不用租户插件重写 SQL），业务代码不可绕过；上下文缺失即拒绝
4. **校验**：所有对象 id 访问先验归属（`tenant_id` 匹配），防跨租户遍历/越权

### 6.3 Agent 层多租户安全（AI × SaaS 的关键差异点）

LLM 输出不可信，**租户隔离不能依赖 prompt，必须靠执行层强制**：

- **会话绑定租户**：每个 Agent 会话（`sessionId` / `userId`）绑定唯一租户；LLM 只接触本租户的语义与数据
- **工具执行强制过滤**：queryCustomers / sendTouch 等所有工具实现内注入当前租户过滤 —— 4.2 工具契约的租户参数由服务端注入，**不由 LLM 提供**
- **对象归属校验**：LLM 生成的 `customer_id` / `campaign_id` 必须属于当前租户，否则工具调用拒绝（防「引用他人对象」）
- **权限继承**：Agent 的 Action 权限 ≤ 发起用户角色权限（RBAC 下放）；高危动作（全量触达、删除）仍按 4.5 转人工审批
- **配置隔离**：模板库、话术风格、通道凭据、频控策略均按租户命名空间读取，不跨租户共享
- **数据级授权（dynamic security）**：audience / campaign 记录 owner（创建人），非 owner 仅审核员/管理员可操作 —— 租户内再按业务条件收敛权限（含 Agent 下放）（D）

### 6.4 计量、配额与计费（SaaS 商业闭环）

| 维度 | 指标 | 控制点 |
|---|---|---|
| 触达 | 发送量 / 到达率 / 通道 | 租户级频控与预算 |
| AI | 会话数 / token 用量 / 工具调用 | 并发会话上限、AI 额度 |
| 存储 | 客户画像 / 事件量 | 容量计费 |

超额策略：阈值告警 → 限流 → 拒绝（配合 3.3 异步队列背压）。

### 6.5 对既有设计的修订

- **数据模型**：全部主表带 `tenant_id`；`(tenant_id, dedup_key)` 复合唯一防跨租户重放；复合索引带租户前缀；agent_run 审计带 `tenant_id`（见 8 节）
- **安全模型（3.5）**：租户隔离升级为本节全链路机制；「缺失租户上下文即拒绝」在数据访问与工具执行两层双重生效
- **落地路线（9 节）**：阶段一即落地租户底座；计量计费随阶段三/四上线

---

## 7. 技术栈

| 层 | 技术 | 角色 |
|---|---|---|
| 前端 | Vue3 + TypeScript + Element Plus | 可视化编排与监控 |
| 后端框架 | Spring Boot + Lombok | 服务编排 |
| 数据访问 | MyBatis-Plus + PostgreSQL（jsonb 画像） | 对象/属性/链接持久化 |
| 队列/缓存 | Redis（事件队列 / 异步任务 / 分布式锁 / 缓存） | 动力层 |
| AI | agentscope-java-2.0（v2.0 GA：`io.agentscope:agentscope-harness`，JDK 17+） | Agent 编排：ReActAgent / HarnessAgent、31 类 Agent 会话事件（实时 UI 渲染）、工具权限系统（allow/审批/deny）、Workspace 沙箱、分布式会话与记忆（Redis / PostgreSQL）、多智能体编排、模型扩展（OpenAI / Anthropic / DashScope / Ollama 等）[6] |
| 构建 | Maven 多模块（api / engine 分层） | — |

---

## 8. 数据模型草案

```
customer      (id, tenant_id, external_id, phone, email, wechat_openid,
               attributes jsonb, status, created_at, updated_at)
audience      (id, tenant_id, name, mode, rule, owner_id, status, created_at)
              -- mode: DYNAMIC|STATIC，rule 与成员互斥；owner_id: dynamic security 归属
audience_member(audience_id, customer_id)   -- 仅 STATIC 人群
campaign      (id, tenant_id, name, audience_id, channel, template_id,
               schedule, gray_ratio, owner_id, status, ...)
template      (id, tenant_id, channel, title, content, vars, status)
channel_config(id, tenant_id, channel, config_encrypted, enabled)
delivery      (id, tenant_id, campaign_id, customer_id, channel, channel_msg_id,
               gray_hit, status, error, attempt, created_at, updated_at)
              -- gray_hit: 灰度抽样命中审计
event         (id, tenant_id, customer_id, event_type, payload jsonb,
               dedup_key UNIQUE, created_at)
agent_run     (id, tenant_id, goal, plan, decisions jsonb, status, created_at)   -- AI 审计
```

---

## 9. 落地路线

| 阶段 | 内容 | 目标 |
|---|---|---|
| 一：基础设施 | 对象模型 + 统一对象 API + 前端对象管理 + 租户隔离底座 | Ontology 底座可用（含多租户） |
| 二：触达闭环 | 通道接入（email/sms/wechat）、任务编排、业务事件 + 异步队列、触达监控 | 多通道自动化触达跑通 |
| 三：规则决策 | 人群条件筛选、灰度/AB、频控与合规 | 规则驱动决策上线 |
| 四：AI 决策 | agentscope 六模块接入、OAG 工具集、人机协同（建议/自动）、agent_run 审计 | 从规则决策演进到 AI 决策 |

---

## 10. 风险与约束

- **LLM 决策不可完全信任** → 默认建议模式；Action 强制业务校验，高危动作转人工
- **重复触达风险** → 幂等去重 + 频控前置，重放安全
- **外部通道不稳定** → 降级策略（无凭据时 console 降级可联调）、失败重试与回执回调
- **数据安全** → 租户隔离强制、凭据加密、Agent 仅经对象 API 与 Action 访问数据
- **跨租户越权（AI 特有风险）** → LLM 输入不可信：工具执行层强制注入租户过滤 + 对象归属校验（6.3），缺租户上下文即拒绝

---

## 11. 参考资料

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

> 一句话总结：**Ontology 底座提供结构化、带状态、实时的业务世界模型与安全 Action 边界；AI-Agent 在其上规划、推理、反思、执行 —— 让 AI 从「建议者」变为「执行者」，完成从数据、洞察到决策、行动的闭环。**