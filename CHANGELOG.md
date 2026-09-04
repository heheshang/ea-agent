# Changelog

本工程变更记录，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- **Call Function 三件套补齐**：`FunctionRegistry`（与 `ActionRegistry` 对称的只读咨询函数注册表）+ 单一 `callFunction` 工具路由——audienceStats / frequencyCheck / channelPreference 收编为注册函数；churnRiskScore 升级为流失预测模型 v1（近 30 天事件衰减 + 非 ACTIVE 状态加成，`model` 版本字段预留 ML 替换）；新增 bestSendTime 最优发送时段优化算法 v1（近 30 天事件 / 成功触达时段分布加权 + 23-06 安静窗口回避 + 无信号回退 10-11 时）
- **Agent 工具收敛**：10 工具 → 7 工具（5 查询 + applyAction + callFunction），引擎系统提示词 v4 同步约束
- **Ontology 链路图**：新增 Function 层（引擎 → 工具 → Action/Function → 对象），运行时统计按 callFunction 入参 name 拆分聚合
- **错误码**：新增 17xxx Function 段（E-17001 Function 未注册），详细设计附录 B 同步

### Fixed

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