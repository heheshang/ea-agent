# Changelog

本工程变更记录，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **调用链明细落库与回放**：新增 `agent_tool_call` 明细表（V5 迁移——tenant_id/run_id/seq/kind/name/target/args/duration_ms/ok/error，索引 `(tenant_id, run_id, seq)` 与 `(tenant_id, created_at)`）；引擎完成时与 `agent_run.tool_calls` 同源批量写入（middleware 采集时补齐 run 内序号 `seq`、工具调用 id、入参解析出的 `target`（applyAction→action、callFunction→name），kind 按 `tool|action|function` 映射；无租户插件，显式写 tenant_id 即可，不新增 SSE 事件，旧 jsonb 保留兼容）。新接口 `GET /api/agent/stats/run-trace?run_id=` 按 seq 升序返回单次 run 的真实调用链（存量 run 无明细行 → 空 trace 不报错）。
- **Ontology 流程图调用链回放**：链路图上方新增「调用链回放」面板——run 下拉（近 30 次，`#id · 时间 · 摘要`）选择后自动播放：逐步点亮真实调用链路（引擎 → 工具 → Action/Function → 对象静态边），当前步节点红色描边 + 光晕、已走过节点淡红描边、活跃边红色虚线 `stroke-dashoffset` 逐帧流动（`@keyframes trace-flow`，0.7s 循环）；播放控制 ⏮/▶/⏸ + 速度 ×1/×2/×4 + 步骤指示（`第 k/N 步 · 工具名 → 目标` + 耗时/失败标记），播放至末尾自动停止；无明细的 run 提示「该 run 无工具调用明细」。
- **Ontology 页对象数据信息**：
  - **对象节点下钻**：Ontology 链路图对象节点标注 `记录数`（租户维度动态安全计数）与 `字段数`（TypeRegistry 定义字段数）；点击对象节点弹出数据下钻弹窗——分页加载 `/api/objects/{type}` 实时对象数据（动态列、`加载更多` 游标翻页、`共 N 条`）。
  - **对象数据总览**：链路图下方新增「对象数据总览」表（7 个对象：记录数/字段数 + 操作列「数据」复用下钻弹窗、「跳转」直达 /customers、/campaigns）。
  - **统计页 Ontology 摘要**：统计看板新增「Ontology 调用链路摘要」卡片——调用总数/失败总数/对象数据量汇总 + 热点 Action/Function/工具 TOP5（调用/失败/失败率/均耗时）+ 对象数据量明细，随 days 单选切换同步刷新。
  - **工作台工具链路标注**：`action_result`/`tool_call` 事件携带链路信息（引擎按 applyAction/callFunction 入参解析动作/函数名），前端经 ontology-graph 拓扑映射为 `工具结果 · applyAction → createCampaign · Campaign 活动` 形态标题；纯查询工具标题保持原样。
- **Ontology 运行时统计增强**：`ontology-graph` 对象节点新增 `count`（ObjectApiService 租户维度计数，复用对象 API 动态安全过滤）与 `fields` 字段。
- **工具结果链路溯源**：SSE `action_result` 事件新增 `chain` 字段（`{"action":"…"}` / `{"function":"…"}`），由工具调用入参增量聚合解析，前端据此标注对象链路。

- **本地启动 .env 自动加载**：应用启动时经 `spring.config.import: optional:file:.env[.properties]` 读取仓库根目录 `.env`；agentscope 模型配置支持 `EA_LLM_*` 回退（优先级 `MODEL_*` → `EA_LLM_*` → 空降级 MockAgentEngine；Spring 占位符用单冒号 `${A:default}` 嵌套）。修复本地 IDEA 运行时（此前 `MODEL_*` 环境变量缺失，日志 `run execute complete durationMs≈1070` 即 MockAgentEngine 固定 5×200ms 延迟，无真实 LLM 调用）。容器路径不变（无 .env、compose 注入优先）。工作区键对齐：引擎改读 `ea.agentscope.workspace-dir`（`AGENT_WORKSPACE` 生效），删除失效的 `ea.agent.workspace-dir`。

- **邮件通道 mock 闭环**：mock-gw 新增 `/email/send`、`/email/receipt`（与短信同范式：X-Api-Key 校验、`mock-email-*` message_id、2s 后回调 DELIVERED 至 `/api/channels/email/callback`）；`EmailChannelAdapter` 由 console 降级升级为配置驱动 HTTP 发送（channel_config 配置存在即走 mock 网关，未配置仍 console 降级兜底）；seed 幂等落库 email 通道配置（endpoint=mock-gw）
- **Call Function 三件套补齐**：`FunctionRegistry`（与 `ActionRegistry` 对称的只读咨询函数注册表）+ 单一 `callFunction` 工具路由——audienceStats / frequencyCheck / channelPreference 收编为注册函数；churnRiskScore 升级为流失预测模型 v1（近 30 天事件衰减 + 非 ACTIVE 状态加成，`model` 版本字段预留 ML 替换）；新增 bestSendTime 最优发送时段优化算法 v1（近 30 天事件 / 成功触达时段分布加权 + 23-06 安静窗口回避 + 无信号回退 10-11 时）
- **Agent 工具收敛**：10 工具 → 7 工具（5 查询 + applyAction + callFunction），引擎系统提示词 v4 同步约束
- **Ontology 链路图**：新增 Function 层（引擎 → 工具 → Action/Function → 对象），运行时统计按 callFunction 入参 name 拆分聚合
- **错误码**：新增 17xxx Function 段（E-17001 Function 未注册），详细设计附录 B 同步
- **触发规则参数优化**：campaign `trigger_rule` 的 cooldown/window 保存时支持宽松格式（`1h`/`30m`/`2d`/`90s`/纯数字秒/ISO-8601）并归一为 ISO-8601 落库（对齐详细设计 8.4 契约 `{"event_type","window":"1d","cooldown":"1h"}`）；非法格式保存即返回 E-13002 明确报错（此前保存成功、触发时 `Duration.parse` 500/DLQ）；event_type trim 并限长 64；创建/更新两路径统一接入，发送侧宽松解析兜底存量脏值；前端触发规则表单占位示例同步

### Changed

- **Ontology 流程图对象拓扑边随调用实线化**：`tool:X→obj`、`action:A→obj`、`function:F→obj` 静态拓扑边原恒为虚线，与「实线 = 有调用」语义冲突（如 `action:sendTouch→obj:delivery` 30 天调用 32 次仍虚线，调用链视觉断裂）。修复：对象边按源节点聚合自带 `calls/avg_ms/fails`，有调用即实线（未调用仍虚线，节点置灰语义保留）；前端对象边只作线型不出现在调用数标签中（`showLabel` 排除 `obj:` 目标），避免对象汇聚处标签拥挤。与幻觉动作名过滤配合，30 天窗口下全图 34 条边全部实线、节点全亮，链路完整。

- **Ontology 调用链路图改为流程图**：由「分层列表 + 文字箭头」改为 SVG 流程图——5 条泳道（引擎/工具/Action/Function/对象）分支连线（实线 = 有调用、虚线 = 未激活静态拓扑），连线改为**正交折线路由**：竖线段走泳道间 38px 列间隙、跨泳道长边走上下两行盒子之间 16px 行间隙带（全程不压任何盒子），箭头落点按目标盒左缘**端口垂直均布**（同一对象多条入边不再汇聚同一点，间距 ≤ 12px），长链边调用次数/均耗时标签锚定行间隙带也不被盒子遮挡。对象节点保留记录数/字段数徽章与点击数据下钻，未调用节点置灰。

### Fixed

- **Ontology 流程图未注册动作调用无痕丢失**：LLM 幻觉动作名（如 `updateCampaignCooldown`，不在 ActionRegistry）经 parseAction 解析成功后被计入 `engine→tool:applyAction` 路由计数、却因无动作节点映射而不生成节点/边，30 天窗口内 9 次调用在图上完全不可见（且 `engine→applyAction` 66 与各 action 边合计 57 不守恒）。修复：聚合循环对解析出的动作/函数名校验注册集（`actionToObject`/`functionToObject`），未注册名直接跳过双累计——路由边计数与 Action/Function 边合计恒等（57==57、59==59），幻觉调用（执行必失败、图上无拓扑）不再污染图数据。

- **Ontology 流程图引擎→路由工具边恒为虚线**：`ontologyGraph` 运行时聚合把 `applyAction`/`callFunction` 的调用仅计入 action/function 拆分聚合（`tool:applyAction→action:X`、`tool:callFunction→function:X` 边），`toolAggs` 永不出现这两个工具名，导致 `engine→tool:applyAction`、`engine→tool:callFunction` 边统计缺失、前端按「未调用」渲染虚线；两条路由工具节点也随之置灰。修复：路由工具自身同样累计 `toolAggs`（引擎→工具边），拆分计数保留（提取 `accumulate` 复用），与统计页工具 TOP 口径一致。

- **Agent 对话触达租户上下文缺失（E-11001）**：`SmsChannelAdapter.validate` 此前经 `TenantContext.requiredTenantId()` 取租户（HTTP 请求线程 ThreadLocal）；Agent 对话中 `applyAction(sendTouch)` 执行于 agentscope 线程（无 TenantContext），短信通道校验必现「租户上下文缺失」，工具结果形如 `{"error":"租户上下文缺失"}`（日志 `action done ok=false`）。通道接口 `validate(Map)` 改为 `validate(Long tenantId, Map)`（与 `send(DeliveryMessage)` 同样显式传租户），`SendTouchAction` 传入 `ActionContext` 租户；Email/Console 适配器签名同步。事件/调度链路（显式重建 TenantContext）与 HTTP 路径行为不变。

- **agentscope 2.0.1 弃用 API 迁移**：`Agent.stream(List<Msg>, StreamOptions)` 已标注 `@Deprecated(since="2.0.0", forRemoval=true)`，引擎迁移至 v2 `HarnessAgent.streamEvents(List<Msg>, RuntimeContext)` → `Flux<AgentEvent>`（RuntimeContext 显式携带 sessionId，保持 v1 `defaultSessionId` 的会话隔离语义）。SSE 契约逐字节不变：`ThinkingBlockDeltaEvent`→`thinking_delta`、`TextBlockDeltaEvent`→`text_delta`、`ToolResultTextDeltaEvent`/`ToolResultEndEvent` 按 toolCallId 聚合→`action_result`（并发多工具各自独立）、`done`/`error` 仍由 AgentService 补齐；摘要改从 `AgentResultEvent` 取最终回复纯文本（不再依赖增量拼接，且不含思考/工具块）。

- **Agent 工具参数契约**：applyAction 描述改为按 ActionRegistry 动态枚举各动作的必需字段（此前仅文案描述，LLM 猜参数名导致反复失败：sendTouch 只缺 `campaign_id` 却传 `tenant_id`/驼峰键）；args 键名驼峰自动归一为下划线（`campaignId` → `campaign_id`）。

- **Agent 工具参数容错**：LLM 以 JSON 字符串形式返回工具 `args` 且采用单引号风格（如 `{'tenant_id': 1, …}`）时，`applyAction`/`callFunction` 的 `JsonUtils.toMap(String)` 抛 `Cannot construct instance of LinkedHashMap`（convertValue 不支持 String→Map）；现改用 `JsonUtils.readMapLenient`——先按标准 JSON 解析，失败后容忍单引号键值/未引号键/尾逗号。

- **Agent 工具结果展示**：真实模型引擎的 `action_result` 事件此前不携带工具名，前端"工具结果"卡片显示字面量 `tool`；现事件携带 `tool` 字段（取自消息内 `ToolResultBlock.getName()`），前端兜底改为 `unknown`。

- **Agent 回答语言**：系统提示词明确所有面向用户的回复一律使用中文（原仅限定"建议"为中文，总结/澄清/拒绝可能输出非中文）；`sys_prompt_version` v4 → v5。

- **Agent 权限下放修复（9.2）**：applyAction 此前以硬编码角色 `AGENT` 构造 ActionContext（ROLE_LEVEL 无此角色 → 0 级），`createCampaign`/`updateCustomerState`/`pauseCampaign` 在 Agent 对话中一律 E-10003 无权限；现透传发起用户 `userId/role`（`role(agent) = role(发起用户)`），权限校验回归 RBAC 矩阵。

## [0.1.0] - 2026-09-05

首个可运行基线：后端全模块 + 前端控制台 + 本地容器化联调栈。

### Added

- **工程骨架**：Maven 多模块（ea-common / ea-api / ea-ontology / ea-agent / ea-channel / ea-app，JDK 17 + Spring Boot 3.4），依赖方向按详细设计 2.1 约束
- **ea-common**：TenantContext 租户上下文、Result / 错误码、幂等器（SETNX + 唯一约束）、信封加密 CryptoService、脱敏工具
- **ea-ontology**：
  - 对象模型：TypeRegistry / ObjectTypeDef / ObjectApiService，类型定义动态化
  - ActionRegistry + `applyAction` 统一动作管线，`action_log` 全量审计
  - EA-Bus 消费（Redis Streams 消费组 + DLQ）、调度器、SHA256 确定性灰度 / AB 分桶
  - 冷却窗 / 频控 / 熔断（Redis `ea:cd:* / ea:fc:* / ea:cb:*`）
- **ea-agent**：agentscope-java 2.0 装配——AgentRunner、单一 `@Tool applyAction` 工具、会话状态机、审批门控、分布式会话记忆（Redis `AgentSession:*`）
- **ea-api**：REST DTO、SSE 协议类（31 类 Agent 会话事件）
- **ea-app**：
  - 控制器：`/api/auth`（JWT 登录）、`/api/agent`（对话 + SSE）、`/api/agent/stats`、`/api/campaigns`、`/api/channels`（含短信回执回调签名校验）、`/api/events`、`/api/objects`、全局异常 → 统一 `Result`
  - Flyway 迁移（V1 初始化 / V2 agent_run 汇总 / V3 agent_run 统计聚合）
  - 演示种子数据：demo 租户 + 双角色账号（`admin/admin123`、`reviewer/reviewer123`）+ DYNAMIC 人群 + 已审核模板 + RUNNING 活动 + console 通道 + 短信通道
- **ea-channel**：console 通道降级适配，sms 通道对接 mock 网关
- **ea-web**：Vue3 + TypeScript + Element Plus 控制台——登录 / 对象管理 / 客户 / 活动编排（CampaignCanvas）/ Agent 对话工作台 / 统计视图；Axios 拦截（X-Tenant-Id / X-Request-Id）、fetch + ReadableStream 的 SSE 客户端
- **mock-gw**：Python 模拟短信网关（发送 + HMAC-SHA256 签名回执回调）
- **容器化**：docker-compose 一键启动（PG16 + Redis7 + mock-gw + ea-app + ea-web），带健康检查与依赖编排
- **设计文档**：总体架构 v1.4、详细设计 v1.6、全链路数据流 v1.4、Ontology-AI 设计 v0.1、技术栈设计 v0.1

[Unreleased]: https://github.com/heheshang/ea-agent/compare/0.1.0...HEAD
[0.1.0]: https://github.com/heheshang/ea-agent/releases/tag/0.1.0