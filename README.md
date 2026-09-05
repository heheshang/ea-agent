# EA-Agent · 智能运营触达系统

> 基于 Ontology 底座的 AI-Agent 多通道运营触达智能体（SaaS 多租户）
> 技术栈：Vue3 + TypeScript + Element Plus ｜ Spring Boot 3.4 + MyBatis-Plus + PostgreSQL 16 + Redis 7 + agentscope-java 2.0

EA-Agent 是智能运营触达平台：以**对象模型（Ontology）**为底座，通过 **EA-Bus（Redis Streams）** 驱动事件流与调度，由 **AI-Agent（agentscope）** 编排人群圈选、触达动作、灰度 / AB 实验与人工审批，覆盖 campaign / delivery / callback / event 全链路。

核心能力：

- **对象模型与 Action 框架**：TypeRegistry / ObjectTypeDef 动态对象模型，ActionRegistry + `applyAction` 统一动作管线
- **EA-Bus 事件流**：Redis Streams 消费组 + DLQ，事件导入 → 人群匹配 → 冷却窗 / 频控 → 触达 → 回执回调
- **AI-Agent**：agentscope ReActAgent，单一 `applyAction` 工具（决策咨询工具只读），31 类会话事件经 SSE 流式回传，高危动作转人工审批
- **灰度 / AB 实验**：SHA256 确定性分桶
- **多租户**：TenantContext + 写路径显式 tenant_id + 复合 FK 存储层兜底（不用租户插件）
- **安全**：JWT 登录 + 信封加密（AES-256-GCM）+ 幂等（SETNX + 唯一约束）+ 动作全量审计

设计定稿文档见 [`docs/`](#设计文档)。

## 技术栈

| 层 | 组件 |
|---|---|
| 运行时 | JDK 17（LTS） |
| 后端 | Spring Boot 3.4 · MyBatis-Plus 3.5 · Flyway |
| 数据库 | PostgreSQL 16（jsonb / 复合 FK / CHECK / 分区） |
| 缓存 / 队列 | Redis 7（幂等 / 冷却窗 / 频控 / 熔断 / Streams / Agent 会话） |
| AI 框架 | agentscope-java 2.0（OpenAI 兼容模型网关，可切 Ollama） |
| 前端 | Vue 3.5 + TypeScript strict + Vite 6 + Element Plus + Pinia |
| 测试 | JUnit 5 · Mockito · Testcontainers |

## 模块划分

Maven 多模块（依赖方向：`common ← api ← ontology ← agent`；`channel` 依赖 `common + ontology`；`app` 依赖全部）：

| 模块 | 职责 |
|---|---|
| `ea-common` | 租户上下文（TenantContext）、Result / 错误码、幂等器、加密（CryptoService）、脱敏 |
| `ea-api` | REST DTO、SSE 协议类（AgentSseEvent） |
| `ea-ontology` | 对象模型（TypeRegistry / ObjectTypeDef / LinkDef）、ActionRegistry + applyAction 管线、EA-Bus 消费、调度器、灰度 / AB 分桶 |
| `ea-agent` | agentscope 装配：AgentRunner、`@Tool` 注册（applyAction）、会话状态机、审批门控、记忆 |
| `ea-channel` | 通道适配器（sms / console 等）、回执回调、mock 网关对接 |
| `ea-app` | 启动装配、REST 控制器（`/api/auth` `/api/agent` `/api/campaigns` `/api/channels` `/api/events` `/api/objects`）、Flyway 迁移、演示数据种子 |

前端独立工程 `ea-web/`（Vite + Vue3 + Element Plus，含 CampaignCanvas 画布、实验面板、Agent 对话工作台）。

## 快速开始

### Docker Compose（推荐）

```bash
git clone git@github.com:heheshang/ea-agent.git
cd ea-agent

# 可选：配置 LLM（AI-Agent 对话需要）
cp .env.example .env   # 填入 EA_LLM_API_KEY / EA_LLM_BASE_URL / EA_LLM_MODEL_ID

docker compose up -d --build
```

启动后：

| 服务 | 地址 | 说明 |
|---|---|---|
| ea-web（前端） | http://localhost:8082 | 运营控制台 |
| ea-app（后端 API） | http://localhost:8081 | REST + SSE |
| mock-gw（短信网关） | :8090 | 模拟短信发送与回执回调 |
| PostgreSQL | localhost:5433 | Flyway 自动迁移 |
| Redis | localhost:6380 | 事件流 / 缓存 |

> 环境变量覆盖：`DB_URL / DB_USER / DB_PASS / REDIS_URL / MODEL_NAME / MODEL_API_KEY / MODEL_BASE_URL`；种子数据由 `EA_SEED=true` 开启（幂等，已有 demo 租户则跳过）。
>
> **本地运行（IDEA / `mvn spring-boot:run`，从仓库根目录启动）**：应用启动时自动加载仓库根目录 `.env`（`spring.config.import: optional:file:.env`）。模型配置优先级：`MODEL_*`（compose 注入）→ `EA_LLM_*`（本地 .env）→ 空（自动降级 MockAgentEngine）。容器内无 .env 文件，仍由 docker-compose 注入，行为不变。

### 演示账号（种子数据）

| 登录名 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `admin` | `admin123` | OPERATOR | 租户 `demo` 管理员 |
| `reviewer` | `reviewer123` | REVIEWER | 审核员（审批门控） |

登录后前端自动携带 `X-Tenant-Id` 调用 API。

### 本地开发

```bash
# 依赖服务（或使用已启动的 compose 实例）
docker compose up -d postgres redis

# 后端（默认 8081）
mvn -pl ea-app spring-boot:run

# 前端（默认 5173，/api /agent 代理到 8081）
cd ea-web
npm install
npm run dev
```

联调顺序建议：租户登录 → 对象 CRUD（幂等头验证）→ 模板 / 通道配置 → 人群 DSL → 任务编排（灰度 → AB）→ 事件导入触发 → Agent 对话 SSE 全链路。

## 目录结构

```
ea-agent/
├── ea-common/ ea-api/ ea-ontology/ ea-agent/ ea-channel/ ea-app/   # Maven 模块
├── ea-web/                     # 前端（Vite + Vue3 + Element Plus）
├── mock-gw/                    # 模拟短信网关（Python）
├── docs/                       # 设计文档
├── docker-compose.yml
└── pom.xml
```

## 设计文档

| 文档 | 版本 | 内容 |
|---|---|---|
| `docs/ea-agent-architecture.md` | v1.4 | 总体架构（分层 / 技术选型 / 时序） |
| `docs/ea-agent-detailed-design.md` | v1.6 | 详细设计（对象模型 / Action / EA-Bus / 调度 / 数据 DDL / 安全） |
| `docs/ea-agent-data-flow.md` | v1.4 | 全链路数据流（导入 → 触达 → 回执） |
| `docs/ea-agent-ontology-ai-design.md` | v0.1 | Ontology + AI 设计草案 |
| `docs/ea-agent-tech-stack.md` | v0.1 | 技术选型与工程基线 |

变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。