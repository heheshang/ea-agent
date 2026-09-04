# Changelog

本工程变更记录，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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