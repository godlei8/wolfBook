# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Wolfbook（狼人杀内容平台）是一个 monorepo，包含三个可独立部署的子项目：

- `backend/` —— Spring Boot 3.5.13 / Java 21 的 REST API + AI 助手
- `admin/` —— Vue 3 + Vite + TypeScript + Element Plus 管理后台
- `miniprogram-vue/` —— uni-app（Vue 3）微信小程序

根目录的 `pom.xml` 是 Maven 聚合工程，**只声明了 `backend` 一个模块**；两个前端是独立的 npm 工程。

## 常用命令

仓库面向 **Windows + PowerShell** 开发环境。辅助脚本优先使用环境变量 `JAVA_HOME`，没有时才退回 `C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot`。

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
- `board-rules.test.mjs`：板子详细规则的整理（空条目过滤、图片地址、序号）、角色主题色按阵营兜底、阵营分组与比例条统计，以及列表卡片没背景图时挑哪三张阵容立绘（`utils/board-rules.js`）。
- `request-errors.test.mjs`：`services/request-errors.js` 里把 HTTP / 网络错误翻译成中文文案的纯函数，以及 4001 判定。

后端测试（`backend/src/test`，H2）：`BackendApplicationTests`（接口冒烟）、`SecurityIntegrationTests`（伪造 token、禁用管理员、匿名/非图片上传、错误信息不泄露）、`JudgeFlowIntegrationTests`（用种子板子 3 跑完整法官流程：权限、身份可见性、座位校验、状态机）、`CommunityIntegrationTests`（下线帖子、评论数、分页上限）、`TokenServiceTests`。新增后端行为时优先往这几个文件里加 MockMvc 用例。

## 架构

### 后端分层
请求链路为 `controller → service → mapper（MyBatis-Plus）→ MySQL`。关键约定：

- **两套 controller**：`controller/api/*`（小程序，`/api/**`）和 `controller/admin/*`（后台，`/admin/**`）。
- **统一响应包装**：所有接口返回 `ApiResponse<T>` = `{ code, msg, data }`。`code == 0` 表示成功；`4001` 表示未认证。`common/` 下的 `GlobalExceptionHandler` + `ApiException` 负责把抛出的异常转换成该结构，所以应当**抛 `ApiException` 而不是手动返回错误响应**。
- **domain 与 entity 分离**：`entity/*` 是 MyBatis-Plus 的表行对象；`domain/*` 是业务逻辑里用的不可变 record；`dto/*` 是请求/响应 record（`WolfbookDtos`、`AssistantDtos`）。`support/DomainConverter` 负责相互转换。不要把 entity 直接暴露给 controller。
- **认证不是 JWT，但有签名**：`support/TokenService` 签发 `base64url(kind:subject:expiresAt).base64url(HmacSHA256)` 形式的 token（kind 为 `user` 或 `admin`），密钥来自 `wolfbook.auth.token-secret`（开发有默认值，生产必须设 `AUTH_TOKEN_SECRET`，换密钥会让所有登录失效），默认 30 天过期。controller 把原始 `Authorization` 头透传给 service，由 service 调用 `tokenService.requireUser` 或 `adminService.requireAdmin`（后者还会回库确认管理员存在且 `status = 1`）。**没有 Spring Security 过滤器链**，鉴权是在每次 service 调用里逐个执行的。
- **上传**：`/api/upload` 和 `/admin/upload` 都先鉴权，再经 `support/ImageUploadValidator` 按文件头校验（只收 jpg/png/gif/webp，≤5MB）。
- **错误响应**：`GlobalExceptionHandler` 对未知异常只记日志并返回通用文案（code 5000），缺 `Authorization` 头返回 4001，JSON 解析失败返回 4000 "请求格式错误"；不要依赖异常 message 透传给前端。
- **分页**：`config/MybatisPlusConfig` 启用了 `PaginationInnerInterceptor`（依赖 `mybatis-plus-jsqlparser`），`selectPage` 是真正的数据库分页。小程序端列表 size 上限 50，后台列表上限 500（`CommunityService.MAX_PAGE_SIZE / ADMIN_MAX_PAGE_SIZE`）。
- **可插拔 Provider**（`support/`）：`AuthProvider`（微信 vs Mock）、`UploadProvider`（腾讯云 COS vs 本地磁盘）、`ContentSafetyProvider`。具体实现由 Spring `@Profile` / 配置选择，因此 `test` 配置会换成 mock 实现（`MockAuthProvider` 等）。
- **数据初始化**：`DatabaseSeeder` 在非 prod 配置下监听 `ApplicationReadyEvent`，灌入演示用的板子/角色/帖子/管理员账号（只在对应表为空时灌）。角色主题色按 `ROLE_THEME_COLORS` 写入，板子 2（预女猎白）和 3（守卫九人局）带完整的详细规则示例。
- **板子与角色的图和规则**：角色有 `portrait`（缩略图）、`fullIllustration`（立绘）、`themeColor`（`#RRGGBB` 专属色，后台校验格式、存大写）。板子有 `coverImage`（列表封面）、`backgroundImage`（每个板子单独配置的横版背景：详情页顶部空则用封面；`/api/boards` 列表卡片顶部空则摆三张阵容立绘，由 `utils/board-rules.js` 的 `pickLineupArt` 挑选，中间放特殊狼、两边放神职，所以列表里的阵容角色也下发 `fullIllustration`），以及 `ruleDetail`：一列 JSON，对应 `domain/BoardRuleDetail`（`overview` 玩法概述、`nightOrder` 夜间行动顺序、`roleRules` 角色细则、`sections` 规则章节）。保存时 `AdminService.normalizeRuleDetail` 会去掉空条目，并要求夜间顺序和角色细则引用的角色都在本板子阵容里、同一角色的细则只有一组，否则 4000。`/api/boards/{id}` 把角色名、头像、阵营和主题色展开进 `ruleDetail`。旧的 `specialRules` 保留，详情页作为「补充规则」显示。小程序端的整理和配色兜底在 `utils/board-rules.js`。

### AI 助手子系统（`service/assistant/`）
这是最复杂的部分，采用**混合 AI 架构**：
- **对话模型**：DeepSeek（`deepseek-v4-flash`），见 `DeepSeekChatService`。
- **向量模型**：火山方舟 Embedding（`VolcengineEmbeddingModel/Service`），存入 **PostgreSQL + pgvector**（Spring AI 的 `PgVectorStore`）。
- **联网搜索兜底**：火山方舟 `web_search`（`VolcengineWebSearchService`）。

回答链路（`AssistantAnswerService`）：结构化板子推荐优先命中站内数据 → 知识类问题走 pgvector 语义检索（`AssistantKnowledgeService`）→ 检索不足时走联网搜索兜底。流式回答用 `SseEmitter` + 虚拟线程（`/api/assistant/ask/stream`）。后台管理端控制 AI 开关、Prompt、检索参数、联网搜索开关以及 DeepSeek Key（`AdminAiController` + `AssistantConfigService`）；火山方舟的 Key 保留在服务端，不下发给后台。

`AssistantAiConfiguration` 装配 pgvector 的 `VectorStore` 和 `JdbcTemplate`。**pgvector 相关 bean 带 `@ConditionalOnProperty(wolfbook.assistant.pg-vector.enabled=true)`**——本地默认关闭，生产开启。

### 电子法官子系统（`JudgeService` + `ApiJudgeController`）
线下面杀的"电子法官"：建房 → 玩家通过分享邀请或输入房号入座 → 准备 → 开局随机发身份 → 主持人逐阶段推进。逻辑全部集中在 `service/JudgeService.java`（约 1400 行），接口在 `/api/judge/rooms/**`（recent、创建、详情、join、ready、start、advance、night-action、vote）。
- **状态机**：房间状态 `lobby → playing → finished`；阶段 `lobby → night_action → day_speech → exile_vote → result`。主持人通过 `advance` 的 action（broadcast / start_vote / resolve_night / resolve_vote / finish）推进，所有 action（含 finish）都要求房间处于 `playing`。状态/阶段切换走 `updateRoomGuarded`（带 WHERE 当前状态的条件更新），并发重复操作会得到 4002 并回滚；入座撞座号由唯一键兜底成 4002。
- **座位校验**：夜间行动目标、`deadSeatNos`、`eliminatedSeatNo` 都必须是在座且存活的玩家（`requireAliveSeat`），否则 4000。
- **胜负**：按存活人数推断的阵营只写进 `winnerSuggestion` 和公告提示，**不会自动结束对局**；只有法官在 `winnerCamp` 里明确传值才会 `finished`（板子规则可能是屠边）。
- **事件流与可见性**：所有动作写入 `judge_action_events`，每条带 visibility（`public` / `system` / `judge_only` / `actor_only`），由 `canViewEvent` 按查看者是法官还是玩家本人过滤——改事件逻辑时注意别把夜间行动泄露给其他玩家。`JudgePlayerView` 不含 openid，只有 `self` 标记；社区接口同样不向小程序下发作者 openid（后台接口保留，用于审核搜索）。
- **法官模式**：目前只开放 `observer`（主持人不入座）；`joined`（系统执法）会直接抛 `4002`"二期开放"。
- **板子支持级别**：`support/JudgeSupportLevels`（`manual_only` / `partial` / `full`，非法值归一为 `manual_only`）。`BoardDetailView.judgeSupportLevel` 目前**固定返回 `manual_only`**，board 表和实体里没有这一列。
- **前端**：`pages/judge/`（index 入口 tab、create 建房、room 房间页轮询刷新）；`utils/judge/meta.js` 是法官模式选项和支持级别的展示文案，`utils/judge/voice.js` 用微信同声传译插件 **WechatSI** 把法官台词转语音播放，插件不可用时自动禁用语音。WechatSI 在 `manifest.json` 中声明，但 `vite.config.js` 里的插件会在 **dev 构建**后把它从 `dist/dev/mp-weixin/app.json` 删掉（开发者工具本地调试时插件常不可用），生产构建保留。

### 数据存储
- **MySQL** 是主存储（约 21 张表：boards、roles、posts、comments、users、assistant_*、judge_* 等，见 `backend/src/main/resources/schema.sql`）。电子法官的 3 张表 `judge_rooms`、`judge_room_players`、`judge_action_events` 同时写在 `schema.sql` 和 `schema-h2.sql` 里；`JudgeSchemaInitializer`（`@Profile("!test")`）在运行时也会 `CREATE TABLE IF NOT EXISTS`，两边改表结构要一起改。
- **PostgreSQL/pgvector** 是**独立的**数据源，仅用于助手的向量存储。
- `application.yml` 设了 `spring.sql.init.mode=never`；建表由 `config/*SchemaInitializer` bean 负责，而不是自动 DDL。`ContentSchemaInitializer` 例外地**不限 profile**，启动时给已有库补 `boards.background_image`、`boards.rule_detail`、`roles.theme_color` 三列（这三列已映射进实体，test profile 连本地 MySQL 时也必须有）；H2 的 `schema-h2.sql` 已带这三列，它检查后什么都不做。

### 配置与 Profile
- 开发配置：`application.yml`（端口 8088）。它会 import `optional:classpath:application-local.yml`——**所有本地密钥（MySQL 密码、DeepSeek / 火山方舟 Key、COS、pgvector 密码）都放这里**，该文件已 gitignore；`application.yml` 里对应项的默认值一律为空，不要再把真实 Key 写回去。
- 生产配置：`application-prod.yml`（端口 8110）——所有密钥来自环境变量（`AUTH_TOKEN_SECRET`、`DB_PASSWORD`、`COS_SECRET_KEY` 是必填，没有默认值），Swagger 关闭，CORS 锁定到生产域名。`docker-compose.prod.yml` 从 `deploy/.env.prod`（gitignore）读取，模板是 `deploy/.env.prod.example`，模板里不要放真实值。
- `test` profile 有两种含义，别混淆：
  - **`mvnw test` 跑单测**：加载 `src/test/resources/application-test.yml`，用 H2 + mock provider；H2 建表脚本是 `backend/src/test/resources/schema-h2.sql`。
  - **`start-backend-dev.ps1`（`spring-boot:run`，默认 `test` profile）**：不带 test classpath，所以**连的是 `application.yml` / `application-local.yml` 里的本地 MySQL**，只是启用了 `MockAuthProvider`（任意 code 都能登录）；同时所有 `@Profile("!test")` 的 SchemaInitializer（Assistant、UserData、Judge）都不会执行（`ContentSchemaInitializer` 仍会执行）。
- 所有可调项都在 `wolfbook.*` 前缀下，由 `config/` 里的 `@ConfigurationProperties` 类绑定（`AssistantProperties`、`WechatProperties`、`UploadProperties`）。

### 前端说明
- **admin**：很薄的 Element Plus SPA，所有后端调用都经过 `admin/src/services/api.ts` 打到 `/admin/**`（开发默认 `http://localhost:8088`）。`unwrap` 遇到 4001 会清 token 并派发 `wolfbook-admin:unauthorized` 事件，`App.vue` 据此回到登录页；其他错误不会登出。帖子/评论/举报列表一次拉 500 条，筛选在前端做。
- **miniprogram-vue**：页面在 `src/pages/`（assistant、boards、community、judge、roles、sessions、user）；API 层在 `src/services/`，由 `request.js` 封装 `uni.request`，负责拆 `ApiResponse` 包装并在 `code 4001` 时清空登录态；网络/HTTP 错误经 `request-errors.js` 翻译成中文，`error.code` 带后端 code。助手流式接口（`assistant.js`）只在 SSE `error` 事件的 `code === 4001` 时清登录态，收到过增量内容后不再回退到非流式重问。助手对话使用增量 markdown 渲染（`services/markdown.js`、`pages/assistant/stream-buffer.js`、`message-model.js`）。全局样式变量（`--page-gutter`、`--card-radius` 等）定义在 `src/App.vue` 的 `page` 选择器上，页面间距应复用这些变量。界面风格是「夜镇卡牌」（设计原型 D）：颜色也是 `page` 上的变量（`--bg` 夜底、`--panel`、`--ink` 描边与硬投影、`--text`、`--gold` 主按钮、`--moon` 选中/链接、`--serif` 标题字体），新页面不要再写死黑金色值；`.glass-card` 是细描线面板，`.ink-card` 是粗描边加硬投影的卡牌。已按原型改完的页面：板子库（`components/BoardCard.vue`）、板子详情、角色图鉴、角色详情；其余页面目前只继承了全局变量。角色头像用共用组件 `components/RoleToken.vue`（主题色描边 + 张数角标），立绘用 `components/RoleArtCard.vue`（3:4 卡牌 + 名牌，无图显示卡背）。开发环境 API 地址在 `.env.development`（`VITE_API_BASE_URL`，应与后端 8088 端口一致）。

## 部署
生产使用 `docker-compose.prod.yml`（服务：`mysql`、带 pgvector 的 `postgres`、`backend`、`admin`、`gateway`）。环境变量模板：`deploy/.env.prod.example`；pgvector 初始化脚本：`deploy/postgres/init/01-enable-vector.sql`。
