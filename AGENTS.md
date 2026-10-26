# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目概览

Wolfbook（狼人杀内容平台）是一个 monorepo，包含三个可独立部署的子项目：

- `backend/` —— Spring Boot 3.5.13 / Java 21 的 REST API + AI 助手
- `admin/` —— Vue 3 + Vite + TypeScript + Element Plus 管理后台
- `miniprogram-vue/` —— uni-app（Vue 3）微信小程序

根目录的 `pom.xml` 是 Maven 聚合工程，**只声明了 `backend` 一个模块**；两个前端是独立的 npm 工程。

## 常用命令

仓库面向 **Windows + PowerShell** 开发环境。辅助脚本把 `JAVA_HOME` 硬编码为 `C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot`，如果你的 JDK 21 不在该路径，需要改脚本。

### 后端（在仓库根目录运行）
```powershell
# 跑测试（使用 H2 + test 配置）
powershell -ExecutionPolicy Bypass -File .\scripts\test-backend.ps1

# 启动开发服务（默认 test 配置，可用 -Profile 覆盖）
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend-dev.ps1
```
直接用 Maven（在 `backend/` 下）：`./mvnw test`、`./mvnw spring-boot:run`、`./mvnw package`。
跑单个测试：`./mvnw test -Dtest=UserServiceTests`（或 `-Dtest=UserServiceTests#方法名`）。

### 管理后台（在 `admin/` 下）
```powershell
npm install
npm run dev       # Vite 开发服务器
npm run build     # vue-tsc 类型检查 + vite 构建
```

### 小程序（在 `miniprogram-vue/` 下）
```powershell
npm install
npm run dev:mp-weixin     # 增量构建 → 用微信开发者工具打开 dist/dev/mp-weixin
npm run build:mp-weixin   # 生产构建
```
前端单测在 `miniprogram-vue/tests/*.test.mjs`，使用 **Node 内置测试运行器**（package.json 里没有配 script）：`node --test "tests/*.test.mjs"`（新版 Node 需要 glob 形式，直接传目录会报模块找不到）。覆盖范围：
- `assistant-*.test.mjs`：助手的 markdown 分段 / 流式缓冲 / 消息模型，以及悬浮球拖拽定位（`utils/assistant-dock.js`）。
- `judge-voice.test.mjs`：电子法官语音脚本生成，以及 WechatSI 插件不可用时的降级。
- `mp-weixin-app-config.test.mjs`：开发构建时从 `app.json` 剔除 WechatSI 插件的构建策略（`build/mp-weixin-app-config.mjs`）。

后端电子法官目前只有 `JudgeSupportLevelsTest`（纯单元测试）；`JudgeService` 没有集成测试，因为 test 配置下不建 `judge_*` 表（见下文）。

## 架构

### 后端分层
请求链路为 `controller → service → mapper（MyBatis-Plus）→ MySQL`。关键约定：

- **两套 controller**：`controller/api/*`（小程序，`/api/**`）和 `controller/admin/*`（后台，`/admin/**`）。
- **统一响应包装**：所有接口返回 `ApiResponse<T>` = `{ code, msg, data }`。`code == 0` 表示成功；`4001` 表示未认证。`common/` 下的 `GlobalExceptionHandler` + `ApiException` 负责把抛出的异常转换成该结构，所以应当**抛 `ApiException` 而不是手动返回错误响应**。
- **domain 与 entity 分离**：`entity/*` 是 MyBatis-Plus 的表行对象；`domain/*` 是业务逻辑里用的不可变 record；`dto/*` 是请求/响应 record（`WolfbookDtos`、`AssistantDtos`）。`support/DomainConverter` 负责相互转换。不要把 entity 直接暴露给 controller。
- **认证不是 JWT**：`support/TokenService` 签发形如 `kind:subject` 的不透明 Base64 token（`user:<openid>` 或 `admin:<username>`）。controller 把原始 `Authorization` 头透传给 service，由 service 调用 `tokenService.requireUser/requireAdmin`。**没有 Spring Security 过滤器链**，鉴权是在每次 service 调用里逐个执行的。
- **可插拔 Provider**（`support/`）：`AuthProvider`（微信 vs Mock）、`UploadProvider`（腾讯云 COS vs 本地磁盘）、`ContentSafetyProvider`。具体实现由 Spring `@Profile` / 配置选择，因此 `test` 配置会换成 mock 实现（`MockAuthProvider` 等）。
- **数据初始化**：`DatabaseSeeder` 在非 prod 配置下监听 `ApplicationReadyEvent`，灌入演示用的板子/角色/帖子/管理员账号。

### AI 助手子系统（`service/assistant/`）
这是最复杂的部分，采用**混合 AI 架构**：
- **对话模型**：DeepSeek（`deepseek-v4-flash`），见 `DeepSeekChatService`。
- **向量模型**：火山方舟 Embedding（`VolcengineEmbeddingModel/Service`），存入 **PostgreSQL + pgvector**（Spring AI 的 `PgVectorStore`）。
- **联网搜索兜底**：火山方舟 `web_search`（`VolcengineWebSearchService`）。

回答链路（`AssistantAnswerService`）：结构化板子推荐优先命中站内数据 → 知识类问题走 pgvector 语义检索（`AssistantKnowledgeService`）→ 检索不足时走联网搜索兜底。流式回答用 `SseEmitter` + 虚拟线程（`/api/assistant/ask/stream`）。后台管理端控制 AI 开关、Prompt、检索参数、联网搜索开关以及 DeepSeek Key（`AdminAiController` + `AssistantConfigService`）；火山方舟的 Key 保留在服务端，不下发给后台。

`AssistantAiConfiguration` 装配 pgvector 的 `VectorStore` 和 `JdbcTemplate`。**pgvector 相关 bean 带 `@ConditionalOnProperty(wolfbook.assistant.pg-vector.enabled=true)`**——本地默认关闭，生产开启。

### 电子法官子系统（`JudgeService` + `ApiJudgeController`）
线下面杀的"电子法官"：建房 → 玩家通过分享邀请或输入房号入座 → 准备 → 开局随机发身份 → 主持人逐阶段推进。逻辑全部集中在 `service/JudgeService.java`（约 1400 行），接口在 `/api/judge/rooms/**`（recent、创建、详情、join、ready、start、advance、night-action、vote）。
- **状态机**：房间状态 `lobby → playing → finished`；阶段 `lobby → night_action → day_speech → exile_vote → result`。主持人通过 `advance` 的 action（broadcast / start_vote / resolve_night / resolve_vote / finish）推进。
- **事件流与可见性**：所有动作写入 `judge_action_events`，每条带 visibility（`public` / `system` / `judge_only` / `actor_only`），由 `canViewEvent` 按查看者是法官还是玩家本人过滤——改事件逻辑时注意别把夜间行动泄露给其他玩家。
- **法官模式**：目前只开放 `observer`（主持人不入座）；`joined`（系统执法）会直接抛 `4002`"二期开放"。
- **板子支持级别**：`support/JudgeSupportLevels`（`manual_only` / `partial` / `full`，非法值归一为 `manual_only`）。`BoardDetailView.judgeSupportLevel` 目前**固定返回 `manual_only`**，board 表和实体里没有这一列。
- **前端**：`pages/judge/`（index 入口 tab、create 建房、room 房间页轮询刷新）；`utils/judge/meta.js` 是法官模式选项和支持级别的展示文案，`utils/judge/voice.js` 用微信同声传译插件 **WechatSI** 把法官台词转语音播放，插件不可用时自动禁用语音。WechatSI 在 `manifest.json` 中声明，但 `vite.config.js` 里的插件会在 **dev 构建**后把它从 `dist/dev/mp-weixin/app.json` 删掉（开发者工具本地调试时插件常不可用），生产构建保留。

### 数据存储
- **MySQL** 是主存储（约 18 张表：boards、roles、posts、comments、users、assistant_* 等，见 `backend/src/main/resources/schema.sql`）。电子法官的 3 张表 `judge_rooms`、`judge_room_players`、`judge_action_events` **不在 `schema.sql` 和 `schema-h2.sql` 里**，而是由 `JudgeSchemaInitializer`（`@Profile("!test")`）启动时建表。因此：H2 单测里没有这几张表；用开发脚本（`test` profile 连本地 MySQL，见下）启动时也不会建表，只有本地库曾经用非 test profile 启动过才会有。
- **PostgreSQL/pgvector** 是**独立的**数据源，仅用于助手的向量存储。
- `application.yml` 设了 `spring.sql.init.mode=never`；建表由 `config/*SchemaInitializer` bean 负责，而不是自动 DDL。

### 配置与 Profile
- 开发配置：`application.yml`（端口 8088）。它会 import `optional:classpath:application-local.yml`——本地密钥覆盖放这里（该文件已被 gitignore）。
- 生产配置：`application-prod.yml`（端口 8110）——所有密钥来自环境变量，Swagger 关闭，CORS 锁定到生产域名。
- `test` profile 有两种含义，别混淆：
  - **`mvnw test` 跑单测**：加载 `src/test/resources/application-test.yml`，用 H2 + mock provider；H2 建表脚本是 `backend/src/test/resources/schema-h2.sql`。
  - **`start-backend-dev.ps1`（`spring-boot:run`，默认 `test` profile）**：不带 test classpath，所以**连的是 `application.yml` / `application-local.yml` 里的本地 MySQL**，只是启用了 `MockAuthProvider`（任意 code 都能登录）；同时所有 `@Profile("!test")` 的 SchemaInitializer（Assistant、UserData、Judge）都不会执行。
- 所有可调项都在 `wolfbook.*` 前缀下，由 `config/` 里的 `@ConfigurationProperties` 类绑定（`AssistantProperties`、`WechatProperties`、`UploadProperties`）。

### 前端说明
- **admin**：很薄的 Element Plus SPA，所有后端调用都经过 `admin/src/services/api.ts` 打到 `/admin/**`。
- **miniprogram-vue**：页面在 `src/pages/`（assistant、boards、community、judge、roles、sessions、user）；API 层在 `src/services/`，由 `request.js` 封装 `uni.request`，负责拆 `ApiResponse` 包装并在 `code 4001` 时清空登录态。助手对话使用增量 markdown 渲染（`services/markdown.js`、`pages/assistant/stream-buffer.js`、`message-model.js`）。全局样式变量（`--page-gutter`、`--card-radius` 等）定义在 `src/App.vue` 的 `page` 选择器上，页面间距应复用这些变量。开发环境 API 地址在 `.env.development`（`VITE_API_BASE_URL`，应与后端 8088 端口一致）。

## 部署
生产使用 `docker-compose.prod.yml`（服务：`mysql`、带 pgvector 的 `postgres`、`backend`、`admin`、`gateway`）。环境变量模板：`deploy/.env.prod.example`；pgvector 初始化脚本：`deploy/postgres/init/01-enable-vector.sql`。
