# Changelog

本工程变更记录，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **createAudience 圈定人群 Action**：Agent 可创建定向人群（`rule` DSL 或 `member_customer_ids` 静态成员二选一；无 rule 且无成员 → E-13002 拒绝，DYNAMIC 落库前 resolve 预览计数、STATIC 先插人群再逐条写 `audience_member`），返回 `{audience_id,name,mode,rule,member_count}`；`queryAudience` 计数修正为 `resolve().size()`（修复 DYNAMIC 恒 0、audience 不存在 NPE），与提示词 v10 一道让 Agent 核对人群规模后再建活动（根因闭环：此前无圈定路径只能绑"活跃客户"全量人群）。对象 API 暴露 `audience_snapshot`（GetCampaign/列表），Web Campaigns 列表新增「目标人群」列与看板「人群：」行。
- **知识库（租户维度 RAG）**：新增 `knowledge` 表（V6 迁移——tenant_id/title/content/tags(jsonb)/enabled/时间戳，索引 `(tenant_id, enabled, updated_at DESC)`）；管理接口 `GET|POST /api/knowledge`、`GET|PUT|DELETE /api/knowledge/{id}`（分页/关键字过滤、物理删除 E-12007）+ `GET /api/knowledge/search` 试检索预览。对话时（AgentscopeAgentEngine 每轮）以同一 pgvector 余弦检索 topK 相关条目注入「【知识库】」用户消息（与「会话回顾」同注入消息模式，HarnessAgent 按 session 缓存不刷 sysPrompt）；检索迁移至 Postgres pgvector（V7 迁移——`embedding vector(256)` 列 + HNSW 索引，compose 镜像随迁 `pgvector/pgvector:pg16`）：词元（CJK 字符 bigram / latin 词）按标题×3/标签×2/内容×1 加权做特征哈希（hashing trick，双哈希 256 维带符号累加 + L2 归一，零外部 embedding API），排名由 SQL `<=>` 余弦距离完成、阈值 0.1 等价旧 2 分下限过滤弱命中文案，另加词覆盖校验剔除哈希碰撞噪声（无共同词不入选，等价旧"无命中不入选"语义）；写入即维护向量、启动幂等回填存量（`embedding IS NULL`），命中得分由 int 加权分改为 double 余弦相似度（0..1，前端展示不变）。`SYS_PROMPT_VERSION` v6→v7（知识库材料优先作业务依据，与实时查询冲突以实时为准），统计 prompt_info 新增 `kb_hits`，`ea.knowledge.top-k`（默认 3）控注入条数。seed 幂等落 3 条演示条目（退订规范/冷却窗/灰度发布）。前端新增「📚 知识库」页（表格 + 新建/编辑对话框 + 启停开关 + 删除确认 + 试检索面板）。

- **MCP 外部工具接入**：`ea.agentscope.mcp.servers` 配置驱动（`name`/`transport`/`url` 或 `command`+`args`+`env`/`headers`；支持 stdio / streamable-http / sse 三传输），Jackson 解析 + 惰性构建（不随应用启动连外部 server），单 server 构建/注册失败降级不阻断；wrapper 按 name 全局缓存、随会话装配进 Toolkit（`mcp_` 前缀工具与本地 7 工具同等注册可见、可被 LLM 调用）。MCP 工具以 ToolBase 注册，库默认权限语义「非只读工具每次调用需人工授权（ASK）」会让无人值守引擎的工具调用悬空（只发确认请求、工具不执行）——引擎装配后按会话切 `PermissionMode.BYPASS` 全量放行（库级 API，持久化到会话槽）。系统提示词约束 2 扩展 MCP 工具域，`SYS_PROMPT_VERSION` v5→v6。
- **Skill 技能体系**：`ea.agentscope.skills-dir`（默认 `agentscope-skills`，随仓库提交；`.agentscope/` 被 gitignore 故不用）经 Layer-2 `skillRepositories` 并入 harness 技能仓库，workspace skills 目录（Layer-3/4）由 harness 默认装配互不干扰；技能经 `load_skill_through_path` 按需加载、提示词自动注入（skilled tools 优先约束）。新增示例技能 `delivery_analysis`（触达复盘分步指引：getCampaign → queryDelivery → 统计 → queryEvents/callFunction → 输出）。

- **调用链明细落库与回放**：新增 `agent_tool_call` 明细表（V5 迁移——tenant_id/run_id/seq/kind/name/target/args/duration_ms/ok/error，索引 `(tenant_id, run_id, seq)` 与 `(tenant_id, created_at)`）；工具调用完成时**实时写入**——运行中的链路即可经 run-trace 实时查询（前端回放无需等 run 结束），引擎完成时按已有 seq **去重兜底**补齐（幂等，实时写失败不丢明细；middleware 采集时补齐 run 内序号 `seq`、工具调用 id、入参解析出的 `target`（applyAction→action、callFunction→name），kind 按 `tool|action|function` 映射；无租户插件，显式写 tenant_id 即可，不新增 SSE 事件，旧 jsonb 保留兼容）。新接口 `GET /api/agent/stats/run-trace?run_id=` 按 seq 升序返回单次 run 的真实调用链（运行中 run 返回已执行部分，存量 run 无明细行 → 空 trace 不报错）。**知识库检索纳入调用链**：引擎每轮会话记忆注入前的知识库检索（pgvector 命中/未命中）落为首步 `seq=1, kind=kb, name=knowledge_search`（`duration_ms`/`ok`；未命中 `ok=false, error=no_hit` 仍保留步骤保障链路完整），实时落库、运行中即可见，工具步骤从 `seq=2` 起算。
- **Ontology 流程图调用链回放**：链路图上方新增「调用链回放」面板——run 下拉（近 30 次，`#id · 时间 · 摘要`）选择后自动播放：逐步点亮真实调用链路（引擎 → 知识库/工具 → Action/Function → 对象静态边），当前步节点红色描边 + 光晕、已走过节点淡红描边、活跃边红色虚线 `stroke-dashoffset` 逐帧流动（`@keyframes trace-flow`，0.7s 循环）；播放控制 ⏮/▶/⏸ + 速度 ×1/×2/×4 + 步骤指示（`第 k/N 步 · 工具名 → 目标` + 耗时/失败标记），播放至末尾自动停止；**运行中 run 可选择回放已执行部分**——选中即自动播放并每 2s 轮询续载增长明细（「执行中 · 实时更新」徽标、终态/切换/卸载自动停止），无明细且运行中提示「该 run 执行中：调用链实时更新，完成后可完整回放…」；无明细的存量 run 提示「该 run 无工具调用明细」。**流程图新增「知识库」泳道**（引擎 → 知识库 → 工具 → Action/Function → 对象）：知识库检索为每次 run 的首步（`kind=kb` 金色节点，engine→kb 边），回放首步点亮知识库节点；**MCP 工具与 Skill 工具动态节点**——`mcp_*`、`load_skill_through_path`、技能内工具被调用过才按出现序入图（此前这些调用聚合并被丢弃、图上不可见），计数守恒。
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
- **客户画像（标签 + 属性）管理**：新增 `customer.tags` jsonb 列（V8 迁移 + GIN 索引，存量默认 `[]`）；管理端客户列表新增标签/属性编辑对话框——标签多选可创建、属性动态键值行 + 常用属性快捷键（gender/birthday/hobbies 等，值支持文本与 JSON）；`PUT /api/objects/customer/{id}` 白名单 attributes/tags 整表替换（管理端所见即所得，非法字段报错、私有列禁改），`UpdateCustomerStateAction` 运行期 attributes 深合并增量（两语义互不干扰）；查询 DSL 新增 JSON 数组列谓词——`tags CONTAINS 'VIP'`/`tags == 'VIP'`（数组包含 `@>`）、`tags != 'VIP'`（不包含）、`tags EXISTS`（非空数组），`queryCustomers` 工具 filter 描述同步示例；同时修复 DSL `EXISTS` 无值操作符解析越界（此前任何 EXISTS 子句均 500）与 `attributes.* EXISTS` 键存在检查（`jsonb_exists`，规避 apply 中 `?` 占位符冲突、无键 EXISTS = 有任意属性）
- **客户列表分页与模糊查询**：客户管理页由游标模式改为 el-pagination 分页（20 条/页、上一页/页码/下一页），固定 `sort=-id` 排序——同秒 created_at 批量数据翻页不重不漏（此前默认排序不定，翻页重复/遗漏），页脚 `共 N 条` 实时总数；页头新增搜索框——`keyword` 参数对姓名（`attributes->>'name'`）/手机/邮箱/外部 ID 任一 `LIKE` 模糊匹配（仅 customer 生效，jsonb tags 列不参与，避免包含语义混淆），与 DSL filter 以 AND 组合，搜索重置回第 1 页

### Changed

- **聊天历史完整回复修复**：`agent_run.summary` 落库不再 600 字截断——完成时写入最终回复全文（`AgentscopeAgentEngine.persistSummary` 删除 `SUMMARY_STORE_LIMIT` 截断；会话记忆注入侧另有 `MEMORY_SUMMARY_LIMIT=200` 截断，prompt 体积受影响为零）。此前 summary 被前端聊天页当完整回复渲染（`rebuildFromHistory`/`renderHistory`），SSE 流式结束后长回复尾部（如多缺口清单的后几项）从聊天区消失；前端 `rebuildFromHistory` 对刚完成的本轮保留 live 流式完整内容，不再被摘要降级。存量 55 条已截断 summary 由 Redis 步骤 `text_delta` 拼接重建回填（只更新数据，不动迁移文件 checksum）。

- **后端源码重构（SRP/OCP/DIP/DRY 收敛）**：常量去重至单一事实源——实体状态/审核/AB 模式常量落位 8 个实体类（`CampaignEntity.STATUS_*`、`DeliveryEntity.STATUS_*`、`TemplateEntity.REVIEW_*`、`AgentRunEntity.STATUS_*` 及 Customer/Tenant/TenantUser/Audience `STATUS_ACTIVE`），跨域共享值新增 `ea-common` 常量类（`Roles` 角色与权限级别矩阵、`Channels` 通道全集、`Actors` 系统/用户/Agent 身份），`AgentService.ST_*` 旧常量删除并全量改引（AgentRunWatchdog 与相关测试同步）；工具方法收敛至 `Texts`（`truncate`/`toSnake`/`toCamel`/`firstValue`）——7 处重复截断逻辑、RuleEngine/ObjectApiService/AgentToolRegistry 三份命名转换、3 处 applyAction/callFunction 入参解析全部委托单一实现（`RuleEngine.toSnake/toCamel` 保留 public static 签名、体内一行委托，测试兼容）；结构收拢——`ObjectApiService` 双 switch 合一（`mapperFor` 委托 `mapperForEntity`，行为逐 case 核对等价）、`EventConsumer.toDlq` 复用 `toStringKeyMap`（error 截断 500 无省略号不变量保留）。纯重构零行为变更（截断/解析语义逐一核对等价，日志与错误文案原样保留），全量测试 37 例通过。

- **Ontology 流程图运行前全虚线、回放激活实线动效**：边线型由「静态数据驱动（有调用即实线）」改为「**回放状态驱动**」——运行前整图所有连线均为灰虚线（拓扑预览态，`engine→tool`/`tool→action·function`/`action·function→obj` 一律虚线，调用数标签与节点徽章保留）；回放每运行到一步，该步经过的节点间连线变为**红色实线**并叠加**流动光点动画**（`edge-line-flow` 亮条沿路径 `@keyframes trace-flow` 循环流动，实线本身为走通的路径），已走过的边保持实线（进度累积），⏮ 重置回到全虚线。节点置灰（无调用）与下钻逻辑不变；后端对象边 `calls/avg_ms/fails` 统计仍保留（标签、节点徽章、统计页数据源）。

- **对象拓扑边统计语义说明**：`action:sendTouch→obj:delivery` 等对象边此前「有调用即实线、无调用虚线」的静态样式已被上一条取代；其携带的源聚合 `calls/avg_ms/fails` 保留，作为调用数标签、对象节点徽章与统计页数据源，线型则完全由回放进度决定。

- **Ontology 调用链路图改为流程图**：由「分层列表 + 文字箭头」改为 SVG 流程图——5 条泳道（引擎/工具/Action/Function/对象）分支连线（实线 = 有调用、虚线 = 未激活静态拓扑），连线改为**正交折线路由**：竖线段走泳道间 38px 列间隙、跨泳道长边走上下两行盒子之间 16px 行间隙带（全程不压任何盒子），箭头落点按目标盒左缘**端口垂直均布**（同一对象多条入边不再汇聚同一点，间距 ≤ 12px），长链边调用次数/均耗时标签锚定行间隙带也不被盒子遮挡。对象节点保留记录数/字段数徽章与点击数据下钻，未调用节点置灰。

### Fixed

- **触达圈定人群失效（活动误发全量客户）**：根因链——(1) 发送时对 `campaign.audience_id` **实时重算**人群、无任何快照：`sendTouch` 每次触发都重新执行 `audience.rule` DSL，人群规则一改（如"跑步"改成全量条件）即波及已建活动；(2) Agent 侧无圈定人群路径，系统仅"活跃客户"（≈全量）与"邮件验证"两个人群，圈定"跑步"的活动实际绑到活跃客户全量（campaign 22 → 10052 客户）；(3) `queryAudience` 对 DYNAMIC 恒报 0 人，Agent 无法核对规模。修复：campaign 新建/改绑人群时经 `AudienceSnapshotService` **固化 `audience_snapshot` jsonb**（audience_id/audience_name/mode/rule/member_count/customer_ids/snapshot_at；V10 迁移），发送只读快照**不再实时重算**（空快照=空发送）；存量无快照活动（campaign 1/22）首次发送前惰性现算并回填；`AudienceResolver` 对 DYNAMIC 空白规则报 DSL_PARSE_ERROR（防"无 WHERE=租户全量"）；同值改绑保留原快照。

- **Ontology 流程图未注册动作调用无痕丢失**：LLM 幻觉动作名（如 `updateCampaignCooldown`，不在 ActionRegistry）经 parseAction 解析成功后被计入 `engine→tool:applyAction` 路由计数、却因无动作节点映射而不生成节点/边，30 天窗口内 9 次调用在图上完全不可见（且 `engine→applyAction` 66 与各 action 边合计 57 不守恒）。修复：聚合循环对解析出的动作/函数名校验注册集（`actionToObject`/`functionToObject`），未注册名直接跳过双累计——路由边计数与 Action/Function 边合计恒等（57==57、59==59），幻觉调用（执行必失败、图上无拓扑）不再污染图数据。

- **Ontology 流程图引擎→路由工具边恒为虚线**：`ontologyGraph` 运行时聚合把 `applyAction`/`callFunction` 的调用仅计入 action/function 拆分聚合（`tool:applyAction→action:X`、`tool:callFunction→function:X` 边），`toolAggs` 永不出现这两个工具名，导致 `engine→tool:applyAction`、`engine→tool:callFunction` 边统计缺失、前端按「未调用」渲染虚线；两条路由工具节点也随之置灰。修复：路由工具自身同样累计 `toolAggs`（引擎→工具边），拆分计数保留（提取 `accumulate` 复用），与统计页工具 TOP 口径一致。

- **Agent 对话触达租户上下文缺失（E-11001）**：`SmsChannelAdapter.validate` 此前经 `TenantContext.requiredTenantId()` 取租户（HTTP 请求线程 ThreadLocal）；Agent 对话中 `applyAction(sendTouch)` 执行于 agentscope 线程（无 TenantContext），短信通道校验必现「租户上下文缺失」，工具结果形如 `{"error":"租户上下文缺失"}`（日志 `action done ok=false`）。通道接口 `validate(Map)` 改为 `validate(Long tenantId, Map)`（与 `send(DeliveryMessage)` 同样显式传租户），`SendTouchAction` 传入 `ActionContext` 租户；Email/Console 适配器签名同步。事件/调度链路（显式重建 TenantContext）与 HTTP 路径行为不变。

- **agentscope 2.0.1 弃用 API 迁移**：`Agent.stream(List<Msg>, StreamOptions)` 已标注 `@Deprecated(since="2.0.0", forRemoval=true)`，引擎迁移至 v2 `HarnessAgent.streamEvents(List<Msg>, RuntimeContext)` → `Flux<AgentEvent>`（RuntimeContext 显式携带 sessionId，保持 v1 `defaultSessionId` 的会话隔离语义）。SSE 契约逐字节不变：`ThinkingBlockDeltaEvent`→`thinking_delta`、`TextBlockDeltaEvent`→`text_delta`、`ToolResultTextDeltaEvent`/`ToolResultEndEvent` 按 toolCallId 聚合→`action_result`（并发多工具各自独立）、`done`/`error` 仍由 AgentService 补齐；摘要改从 `AgentResultEvent` 取最终回复纯文本（不再依赖增量拼接，且不含思考/工具块）。

- **Agent 工具参数契约**：applyAction 描述改为按 ActionRegistry 动态枚举各动作的必需字段（此前仅文案描述，LLM 猜参数名导致反复失败：sendTouch 只缺 `campaign_id` 却传 `tenant_id`/驼峰键）；args 键名驼峰自动归一为下划线（`campaignId` → `campaign_id`）。

- **Agent 工具参数容错**：LLM 以 JSON 字符串形式返回工具 `args` 且采用单引号风格（如 `{'tenant_id': 1, …}`）时，`applyAction`/`callFunction` 的 `JsonUtils.toMap(String)` 抛 `Cannot construct instance of LinkedHashMap`（convertValue 不支持 String→Map）；现改用 `JsonUtils.readMapLenient`——先按标准 JSON 解析，失败后容忍单引号键值/未引号键/尾逗号。

- **Agent 工具结果展示**：真实模型引擎的 `action_result` 事件此前不携带工具名，前端"工具结果"卡片显示字面量 `tool`；现事件携带 `tool` 字段（取自消息内 `ToolResultBlock.getName()`），前端兜底改为 `unknown`。

- **Agent 回答语言**：系统提示词明确所有面向用户的回复一律使用中文（原仅限定"建议"为中文，总结/澄清/拒绝可能输出非中文）；`sys_prompt_version` v4 → v5。

- **Agent 权限下放修复（9.2）**：applyAction 此前以硬编码角色 `AGENT` 构造 ActionContext（ROLE_LEVEL 无此角色 → 0 级），`createCampaign`/`updateCustomerState`/`pauseCampaign` 在 Agent 对话中一律 E-10003 无权限；现透传发起用户 `userId/role`（`role(agent) = role(发起用户)`），权限校验回归 RBAC 矩阵。
- **6 项行为缺口修复**：（1）`AgentService.handle` 事件 switch 补 `approval_required` 分支——事件到达即置 run 为 `AWAITING_APPROVAL` 并落库（此前事件被 default 吞掉、run 滞留 PLANNING，`approve()` 因状态不匹配永远 E-15002）；（2）`AgentToolRegistry.QueryCustomers` 删除恒等 `trim`（remove+put 同 key 同值，HashMap 上纯死代码，输出零变化）；（3）`SendTouchAction.isUnsubscribed` 删除空 if 死块（仅注释无 body），注释并入保留的 contact guard；（4）`POST /api/channels/{type}/callback` 补回执状态白名单——仅 `DELIVERED/BOUNCED/FAILED/UNSUBSCRIBED` 四终端态可回写，`PENDING/SENT` 内部态及任意非法值一律 E-10001 拒绝（校验位于验签/重放窗之后、幂等去重之前，非法值不消耗 `ea:cb:` 去重键）；（5）`sha256Hex` 三份收敛为 `Texts.sha256Hex`（UTF-8 + 小写 hex，输出逐字节等价），删 SendTouchAction/SeedDataInitializer 私有实现及随之失效的 imports；（6）详细设计 docs 对齐代码——4.3 run 状态枚举块改为 `AgentRunEntity.STATUS_*` 字符串常量说明、状态机实现引用由不存在的 `AgentStateMachine` 改为 `AgentService.handle/execute` 事件驱动描述。

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