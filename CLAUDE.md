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
- `assistant-*.test.mjs`：助手的 markdown 渲染 / 流式缓冲 / 消息模型。
- `judge-voice.test.mjs`：电子法官语音脚本生成，以及 WechatSI 插件不可用时的降级。
- `mp-weixin-app-config.test.mjs`：开发构建时从 `app.json` 剔除 WechatSI 插件的构建策略（`build/mp-weixin-app-config.mjs`）。
- `board-rules.test.mjs`：板子详细规则的整理（空条目过滤、图片地址、序号）、角色主题色按阵营兜底、阵营分组与比例条统计，以及列表卡片没背景图时挑哪几张阵容立绘（`utils/board-rules.js`）。
- `judge-night-order.test.mjs`：法官主持台的夜间步骤（板子没配夜间顺序时的通用顺序、狼队合并成一步）、女巫剩余用药、查验结果（`utils/judge/night-order.js`）。
- `request-errors.test.mjs`：`services/request-errors.js` 里把 HTTP / 网络错误翻译成中文文案的纯函数，以及 4001 判定。

后端测试（`backend/src/test`，H2）：`BackendApplicationTests`（接口冒烟，含后台能清空角色立绘/主题色）、`SecurityIntegrationTests`（伪造 token、禁用管理员、匿名/非图片上传、错误信息不泄露）、`JudgeFlowIntegrationTests`（用种子板子 3 跑完整法官流程：权限、身份可见性、座位校验、状态机、短房号、法官代记夜间行动）、`CommunityIntegrationTests`（下线帖子、评论数、分页上限、帖子标题/分类/关联板子筛选、只看我的）、`TokenServiceTests`，以及助手的纯逻辑单测 `AssistantIntentRouterTests`（问题分流）、`BoardRecommenderTests`（板子打分）。新增后端行为时优先往这几个文件里加 MockMvc 用例。

## 架构

### 后端分层
请求链路为 `controller → service → mapper（MyBatis-Plus）→ MySQL`。关键约定：

- **两套 controller**：`controller/api/*`（小程序，`/api/**`）和 `controller/admin/*`（后台，`/admin/**`）。
- **统一响应包装**：所有接口返回 `ApiResponse<T>` = `{ code, msg, data }`。`code == 0` 表示成功；`4001` 表示未认证。`common/` 下的 `GlobalExceptionHandler` + `ApiException` 负责把抛出的异常转换成该结构，所以应当**抛 `ApiException` 而不是手动返回错误响应**。
- **domain 与 entity 分离**：`entity/*` 是 MyBatis-Plus 的表行对象；`domain/*` 是业务逻辑里用的不可变 record；`dto/*` 是请求/响应 record（`WolfbookDtos`、`AssistantDtos`）。`support/DomainConverter` 负责相互转换。不要把 entity 直接暴露给 controller。
- **认证不是 JWT，但有签名**：`support/TokenService` 签发 `base64url(kind:subject:expiresAt).base64url(HmacSHA256)` 形式的 token（kind 为 `user` 或 `admin`），密钥来自 `wolfbook.auth.token-secret`（开发有默认值，生产必须设 `AUTH_TOKEN_SECRET`，换密钥会让所有登录失效），默认 30 天过期。controller 把原始 `Authorization` 头透传给 service，由 service 调用 `tokenService.requireUser` 或 `adminService.requireAdmin`（后者还会回库确认管理员存在且 `status = 1`）。**没有 Spring Security 过滤器链**，鉴权是在每次 service 调用里逐个执行的。
- **上传**：`/api/upload` 和 `/admin/upload` 都先鉴权，再经 `support/ImageUploadValidator` 按文件头校验（只收 jpg/png/gif/webp，≤5MB）。
- **可清空字段**：MyBatis-Plus 的 `updateById` 默认跳过 null 字段。后台允许清空的可选列（角色 `alias`、`background`、`portrait`、`fullIllustration`、`themeColor`，板子 `backgroundImage`）在实体上标了 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`；新增这类字段要照做，否则后台清空不会落库。
- **板子上下架与人数校验**：`PATCH /admin/boards/{id}/status`（`status` 为 `1`/`0`）切换上下架，`PUT` 保存板子不改状态；下架后 `/api/boards` 列表、`/api/boards/{id}` 详情（4004「板子已下架」）和建法官房都看不到它，已开局的房间不受影响。保存板子时阵容人数之和必须等于 `playerCount`，否则 4000。`/admin/summary` 除了总数还给出上架数、缺背景图的板子数、缺立绘的角色数、AI 今日提问 / 失败数和最近改过的 5 个板子。
- **世界观（夜镇编年史）**：设定原文在 `docs/design/world-lore.md`（全部原创，不用任何商业作品的人名和故事）。角色的 `alias` 列现在是**真名**（后台显示「真名」），另有 `title` 称号和 `relations` 人物关系（JSON `[{roleId, label}]`，保存时校验对方存在、不能是自己、同一角色只写一条、最多 8 条），`/api/roles/{id}` 把关系展开成名字、真名和头像。小镇简介、三方势力（camp 为 `good` / `wolf` / `third`）和编年史事件存在 `world_lore` 表 id = 1 的一行 JSON（`domain/WorldLore`），后台 `GET/PUT /admin/world` 整份读写，小程序 `GET /api/world` 读（事件出场角色已展开）。初始内容由 `deploy/sql/2026-10-28-world-lore.sql` 灌入（按角色名找 id，可重复执行）。AI 助手知识库会带上真名、称号、身世、关系和一份 `WORLD:1` 世界观文档。
- **加新角色**：写作规范在 `backend/src/main/resources/prompts/role-writing-guide.md`（人看，也是 AI 起草的提示词）。后台设定完整度规则在 `admin/src/utils/roleCompleteness.ts`：必需项（立绘、头像、真名、称号、身世 ≥ 80 字、关系 ≥ 2 条）缺了就标「设定不完整」，建议项（跨阵营关系、编年史出场）只提示。`POST /admin/roles/draft`（`RoleDraftService`）把规范、世界观和现有角色交给 DeepSeek 起草真名、称号、身世和关系，只返回草稿不落库；没配 Key 返回 4000，DeepSeek 余额不足等错误翻成中文提示（5000）。保存角色后，关系里写到但还没写回来的角色会提示去补。
- **社区帖子**：帖子带 `title`（≤40）、`category`（战报 / 教学 / 讨论，默认讨论）、关联板子 `boardId` / `boardName`（创建时按 id 回库取名，板子不存在 4000）。`GET /api/posts` 支持 `sort=hot|latest`、`category`、`boardId`、`mine=true`（需登录，只看自己的帖子）。
- **错误响应**：`GlobalExceptionHandler` 对未知异常只记日志并返回通用文案（code 5000），缺 `Authorization` 头返回 4001，JSON 解析失败返回 4000 "请求格式错误"；不要依赖异常 message 透传给前端。
- **分页**：`config/MybatisPlusConfig` 启用了 `PaginationInnerInterceptor`（依赖 `mybatis-plus-jsqlparser`），`selectPage` 是真正的数据库分页。小程序端列表 size 上限 50，后台列表上限 500（`CommunityService.MAX_PAGE_SIZE / ADMIN_MAX_PAGE_SIZE`）。
- **可插拔 Provider**（`support/`）：`AuthProvider`（微信 vs Mock）、`UploadProvider`（腾讯云 COS vs 本地磁盘）、`ContentSafetyProvider`。具体实现由 Spring `@Profile` / 配置选择，因此 `test` 配置会换成 mock 实现（`MockAuthProvider` 等）。
- **数据初始化**：`DatabaseSeeder` 在非 prod 配置下监听 `ApplicationReadyEvent`，灌入演示用的板子/角色/帖子/管理员账号（只在对应表为空时灌）。角色主题色按 `ROLE_THEME_COLORS` 写入，板子 2（预女猎白）和 3（守卫九人局）带完整的详细规则示例。
- **板子与角色的图和规则**：角色有 `portrait`（缩略图）、`fullIllustration`（立绘）、`themeColor`（`#RRGGBB` 专属色，后台校验格式、存大写）。板子有 `coverImage`（列表封面）、`backgroundImage`（每个板子单独配置的横版背景：详情页顶部空则用封面；`/api/boards` 列表卡片顶部空则摆三张阵容立绘；首页「今晚推荐」是走马灯（`swiper`，最多 5 个，配了背景图的排前面），由 `utils/board-rules.js` 的 `pickLineupArt` 挑选，中间放特殊狼、两边放神职，所以列表里的阵容角色也下发 `fullIllustration`），以及 `ruleDetail`：一列 JSON，对应 `domain/BoardRuleDetail`（`overview` 玩法概述、`nightOrder` 夜间行动顺序、`roleRules` 角色细则、`sections` 规则章节）。保存时 `AdminService.normalizeRuleDetail` 会去掉空条目，并要求夜间顺序和角色细则引用的角色都在本板子阵容里、同一角色的细则只有一组，否则 4000。`/api/boards/{id}` 把角色名、头像、阵营和主题色展开进 `ruleDetail`。旧的 `specialRules` 保留，详情页作为「补充规则」显示。小程序端的整理和配色兜底在 `utils/board-rules.js`。

### AI 助手子系统（`service/assistant/`）
这是最复杂的部分，采用**混合 AI 架构**：
- **对话模型**：DeepSeek（`deepseek-v4-flash`），见 `DeepSeekChatService`。
- **向量模型**：火山方舟 Embedding（`VolcengineEmbeddingModel/Service`），存入 **PostgreSQL + pgvector**（Spring AI 的 `PgVectorStore`）。
- **联网搜索兜底**：火山方舟 `web_search`（`VolcengineWebSearchService`）。

回答链路：`AssistantAnswerService` 只管鉴权、会话、落库和日志，怎么答在 `AssistantAnswerPipeline`（普通接口、流式接口、后台调试台共用这一条）：
- **分流** `AssistantIntentRouter`（纯规则，有单测）：命中拦截词 → 婉拒；在挑板子（推荐 / 玩什么 / 只报人数难度）→ 推荐；点了具体板子名、角色名、在详情页里问 → 按资料回答。「预女猎白这个板子女巫能自救吗」不是推荐。
- **推荐** `BoardRecommender`：按人数（认中文数字）、难度和标签的常见说法、阵容里的角色给上架板子打分，有对得上的就只给对得上的（最多 3 个），模型只负责介绍候选。
- **按资料回答**：问题里点名的板子 / 角色直接取结构化资料全文；追问（「第一个具体怎么玩」）取上一轮回答里提到的板子；再加 `AssistantKnowledgeService` 检索（向量出错时退回关键词）。站内什么都没有且开了联网搜索才走火山 `web_search`；否则也交给模型按通用规则答。
- **页面上下文** `AssistantPageContext`：小程序 `openAssistant` 带的 `pageContext`（板子 / 角色详情、法官房）会把对应资料全文写进系统提示词，模型才知道「这个板子」「她」是谁。
- **提示词** `AssistantPromptFactory`：后台的系统 Prompt 只是人设；格式（先给结论、不套固定段落、不用大标题）、只用站内资料、不替玩家做局中决策这些规矩写在代码里。历史对话按多轮消息传。
- **流式事件**：`started` → `stage`（「正在查站内资料」等进度，小程序在出字前显示等待卡片）→ `delta` → `done`；准备阶段出错（未登录、助手关闭）才发 `error`。流程里出意外也会给一句话、存进会话并记成失败日志。
- 结构化资料（板子含阵容、角色含称号 / 真名 / 身世 / 人物关系、世界观）只在数据改动时由 `requestStructuredKnowledgeRebuild` 同步，空库启动时补一次，**不再每问一次全量重写**；要进检索仍需在后台「发布中心」发布。
- 评测：`node scripts/assistant-eval.mjs http://127.0.0.1:8088 out.md` 把 `scripts/assistant-eval-questions.json` 的 19 题问一遍出报告（会真调模型，只对本地跑）。

流式回答用 `SseEmitter` + 虚拟线程（`/api/assistant/ask/stream`）。后台「AI 助手」页：控制台（开关、人设、拦截词）、模型管理、**调试台**（`POST /admin/ai/playground`，用当前配置答一题，返回分流理由、命中资料、各步耗时，不落库）、知识库、发布中心、问答日志（最近 500 条，可筛失败 / 慢的，点开看当时的回答）。火山方舟的 Key 保留在服务端，不下发给后台。

**对话模型可切换**：后台「AI 助手 → 模型管理」登记多个 OpenAI 兼容模型（`assistant_models` 表：接口地址、模型编码、Key、启用、`disable_thinking`、`active`），`AssistantConfigService.getRuntimeConfig()` 把 provider 换成 `active = 1` 的那一个，问答和「按世界观起草」都跟着切；一个都没设时退回 `assistant_config` 里原来的 DeepSeek 配置（第一次打开模型管理会把它搬进来当第一项）。`DeepSeekChatService` 实际是通用的 OpenAI 兼容客户端（`{baseUrl}/chat/completions`）；模型开了「关闭思考」会带 `chat_template_kwargs: {thinking: false, enable_thinking: false}`，给 vLLM 部署的推理模型（比如学校的 `deepseek-v4-flash-0731`）用，不开它每次先想几十秒到几分钟。Key 只存在后端，接口返回打码值（`********` + 后 4 位），原样传回表示不改。没开 pgvector 时知识检索走 `AssistantKnowledgeService` 的关键词兜底：中文按相邻两字切词（`fallbackTokens`），资料要在「发布中心」发布后才会被检索到。

`AssistantAiConfiguration` 装配 pgvector 的 `VectorStore` 和 `JdbcTemplate`。**pgvector 相关 bean 带 `@ConditionalOnProperty(wolfbook.assistant.pg-vector.enabled=true)`**——本地默认关闭。生产原本开启，2026-10-29 起在服务器 `deploy/.env.prod` 里设了 `ASSISTANT_PGVECTOR_ENABLED=false`：火山方舟的豆包文本向量模型已下线（接口 404），新的 `doubao-embedding-vision-251215` 要在方舟控制台开通、走 `/embeddings/multimodal` 接口，接上之前线上只用关键词检索。

### 电子法官子系统（`JudgeService` + `ApiJudgeController`）
线下面杀的"电子法官"：建房 → 玩家通过分享邀请或输入房号入座 → 准备 → 开局随机发身份 → 主持人逐阶段推进。逻辑全部集中在 `service/JudgeService.java`（约 1400 行），接口在 `/api/judge/rooms/**`（recent、创建、详情、join、ready、start、advance、night-action、vote）。
- **短房号**：建房时生成 4 位数字 `room_code`（在未结束的房间里唯一，撞满退到 6 位）。所有公开接口的 `{roomId}` 既可以是真实房间号也可以是 4~6 位短房号，由 `resolveRoomId` 解析（优先未结束的房间，找不到 4004）。
- **法官代记**：线下面杀时玩家闭眼指人，`night-action` 请求带 `actorSeatNo` 时表示法官替这个座位记录（只有本房法官能这样调，否则 4003；座位必须在座且存活）。快照里 `nightActions` 是今晚的记录，`nightHistory` 是历次夜晚的全部记录（带 `dayNo`）：对局中只给法官，结束后全场可见，结果页用它写每个人的战绩。
- **状态机**：房间状态 `lobby → playing → finished`；阶段 `lobby → night_action → day_speech → exile_vote → result`。主持人通过 `advance` 的 action（broadcast / start_vote / resolve_night / resolve_vote / finish）推进，所有 action（含 finish）都要求房间处于 `playing`。状态/阶段切换走 `updateRoomGuarded`（带 WHERE 当前状态的条件更新），并发重复操作会得到 4002 并回滚；入座撞座号由唯一键兜底成 4002。
- **座位校验**：夜间行动目标、`deadSeatNos`、`eliminatedSeatNo` 都必须是在座且存活的玩家（`requireAliveSeat`），否则 4000。
- **胜负**：按存活人数推断的阵营只写进 `winnerSuggestion` 和公告提示，**不会自动结束对局**；只有法官在 `winnerCamp` 里明确传值才会 `finished`（板子规则可能是屠边）。
- **事件流与可见性**：所有动作写入 `judge_action_events`，每条带 visibility（`public` / `system` / `judge_only` / `actor_only`），由 `canViewEvent` 按查看者是法官还是玩家本人过滤——改事件逻辑时注意别把夜间行动泄露给其他玩家。`JudgePlayerView` 不含 openid，只有 `self` 标记；社区接口同样不向小程序下发作者 openid（后台接口保留，用于审核搜索）。
- **法官模式**：目前只开放 `observer`（主持人不入座）；`joined`（系统执法）会直接抛 `4002`"二期开放"。
- **板子支持级别**：`support/JudgeSupportLevels`（`manual_only` / `partial` / `full`，非法值归一为 `manual_only`）。`BoardDetailView.judgeSupportLevel` 目前**固定返回 `manual_only`**，board 表和实体里没有这一列。
- **前端**：`pages/judge/`（index 入口 tab、create 建房、room 房间页轮询刷新）。`room.vue` 按状态和身份切四种视图：大厅（等待入座）、法官主持台（按夜间步骤逐个角色引导，女巫/预言家/守卫/猎人等有专门的代记按钮，天亮时按刀口、解药、守护、毒药预填出局座位）、玩家翻身份牌（按住翻牌、松手盖牌）、对局结束。夜间步骤来自 `utils/judge/night-order.js`：板子配了 `ruleDetail.nightOrder` 就用它（狼队合并成一步），没配就按通用顺序从阵容里挑，板子详情页也显示这份兜底顺序。`utils/judge/meta.js` 是法官模式选项和支持级别的展示文案，`utils/judge/voice.js` 用微信同声传译插件 **WechatSI** 把法官台词转语音播放，插件不可用时自动禁用语音。WechatSI 在 `manifest.json` 中声明，但 `vite.config.js` 里的插件会在 **dev 构建**后把它从 `dist/dev/mp-weixin/app.json` 删掉（开发者工具本地调试时插件常不可用），生产构建保留。

### 数据存储
- **MySQL** 是主存储（约 21 张表：boards、roles、posts、comments、users、assistant_*、judge_* 等，见 `backend/src/main/resources/schema.sql`）。电子法官的 3 张表 `judge_rooms`、`judge_room_players`、`judge_action_events` 同时写在 `schema.sql` 和 `schema-h2.sql` 里；`JudgeSchemaInitializer`（`@Profile("!test")`）在运行时也会 `CREATE TABLE IF NOT EXISTS`，两边改表结构要一起改。
- **PostgreSQL/pgvector** 是**独立的**数据源，仅用于助手的向量存储。
- `application.yml` 设了 `spring.sql.init.mode=never`；建表由 `config/*SchemaInitializer` bean 负责，而不是自动 DDL。`ContentSchemaInitializer` 例外地**不限 profile**，启动时给已有库补列：`boards.background_image`、`boards.rule_detail`、`roles.theme_color`、`posts.title / category / board_id / board_name`、`judge_rooms.room_code`（这些列已映射进实体，test profile 连本地 MySQL 时也必须有；表还不存在时跳过）；H2 的 `schema-h2.sql` 已带这些列，它检查后什么都不做。

### 配置与 Profile
- 开发配置：`application.yml`（端口 8088）。它会 import `optional:classpath:application-local.yml`——**所有本地密钥（MySQL 密码、DeepSeek / 火山方舟 Key、COS、pgvector 密码）都放这里**，该文件已 gitignore；`application.yml` 里对应项的默认值一律为空，不要再把真实 Key 写回去。
- 生产配置：`application-prod.yml`（端口 8110）——所有密钥来自环境变量（`AUTH_TOKEN_SECRET`、`DB_PASSWORD`、`COS_SECRET_KEY` 是必填，没有默认值），Swagger 关闭，CORS 锁定到生产域名。`docker-compose.prod.yml` 从 `deploy/.env.prod`（gitignore）读取，模板是 `deploy/.env.prod.example`，模板里不要放真实值。
- `test` profile 有两种含义，别混淆：
  - **`mvnw test` 跑单测**：加载 `src/test/resources/application-test.yml`，用 H2 + mock provider；H2 建表脚本是 `backend/src/test/resources/schema-h2.sql`。
  - **`start-backend-dev.ps1`（`spring-boot:run`，默认 `test` profile）**：不带 test classpath，所以**连的是 `application.yml` / `application-local.yml` 里的本地 MySQL**，只是启用了 `MockAuthProvider`（任意 code 都能登录）；同时所有 `@Profile("!test")` 的 SchemaInitializer（Assistant、UserData、Judge）都不会执行（`ContentSchemaInitializer` 仍会执行）。
- 所有可调项都在 `wolfbook.*` 前缀下，由 `config/` 里的 `@ConfigurationProperties` 类绑定（`AssistantProperties`、`WechatProperties`、`UploadProperties`）。

### 前端说明
- **admin**：夜镇卡牌风格（和小程序同一套色值，设计稿 https://claude.ai/artifact/QSTGNKRgjBqcymm91EoFBt ），通用样式是 `src/style.css` 里的全局 `nt-*` 类，Element Plus 只剩 AI 助手页、弹窗和消息提示在用（已改成夜镇配色）。页面用 hash 路由（`utils/route.ts`：`#/overview`、`#/boards`、`#/boards/new`、`#/boards/{id}`、`#/roles`、`#/world`、`#/community`、`#/ai`），不引入 vue-router。板子是列表（`components/boards/BoardList.vue`）+ 整页编辑（`BoardEditor.vue`，右侧 `BoardPreview.vue` 实时预览小程序详情页和列表卡片），阵营分组、立绘挑选、首页推荐顺序、通用夜间顺序这些和小程序同口径的逻辑在 `utils/board.ts`。所有后端调用都经过 `admin/src/services/api.ts` 打到 `/admin/**`（开发默认 `http://localhost:8088`）。`unwrap` 遇到 4001 会清 token 并派发 `wolfbook-admin:unauthorized` 事件，`App.vue` 据此回到登录页；其他错误不会登出。帖子/评论/举报列表一次拉 500 条，筛选在前端做。
- **miniprogram-vue**：页面在 `src/pages/`（assistant、boards、community、judge、roles、sessions、user）；API 层在 `src/services/`，由 `request.js` 封装 `uni.request`，负责拆 `ApiResponse` 包装并在 `code 4001` 时清空登录态；网络/HTTP 错误经 `request-errors.js` 翻译成中文，`error.code` 带后端 code。助手流式接口（`assistant.js`）只在 SSE `error` 事件的 `code === 4001` 时清登录态，收到过增量内容后不再回退到非流式重问。助手对话使用增量 markdown 渲染（`services/markdown.js`、`pages/assistant/stream-buffer.js`、`message-model.js`）。界面风格是「夜镇卡牌」（设计原型 D，所有页面都已按原型重做）：颜色是 `src/App.vue` 里 `page` 上的变量（`--bg` 夜底、`--panel`、`--ink` 描边与硬投影、`--text`、`--gold` 主按钮、`--moon` 选中/链接、`--serif` 标题字体），原型按 390 宽画，1px = 2rpx。通用样式全是 `App.vue` 里的全局 `nt-*` 类（`nt-page`、`nt-gutter`、`nt-panel` 细描线面板、`nt-ink` 粗描边加硬投影、`nt-btn--gold/ghost/cream`、`nt-chip`、`nt-token` 角色头像、`nt-card` 3:4 立绘卡（无图显示卡背）、`nt-camp` 阵营比例条、`nt-bar` 底部操作栏等），新页面直接用，不要写死色值。WXSS 不支持 `*` 通配选择器（H5 正常，但开发者工具会整包报「编译 .wxss 文件错误」），写样式只用类选择器。**不要在自定义组件里再嵌自定义组件**（微信开发者工具里嵌套的组件曾整块不渲染），所以角色头像、立绘卡都是页面里的内联结构加全局类；页面直接用一层组件没问题（`BoardCard`、`NavBar`）。
  - **导航**：所有页面 `navigationStyle: custom`，用 `components/NavBar.vue`（`back` / `overlay` 浮在大图上 / `wordmark`，默认插槽放标题，`#side` 插槽放跟在返回键右边的按钮——右上角是微信胶囊）。高度和胶囊避让来自 `utils/nav.js` 的 `getNavInsets()`。
  - **tabBar**：`pages.json` 里 `tabBar.custom: true`，原生自定义组件在 `src/custom-tab-bar/`（uni 原样拷到产物），顺序是 板子 / 笔记（`pages/sessions/index`）/ 开局（中间金色按钮）/ 社区 / 我的；角色图鉴（`pages/roles/list`）是普通页面，入口在首页「全部板子」右侧和「我的」菜单，跳转用 `navigateTo`；`list` 仍保留作 H5 和兜底。高亮由每个 tab 页在 `onShow` 里调 `utils/tabbar.js` 的 `syncTabBar()` 同步（组件自己取页面栈拿不准当前页），新增 tab 页要照做。新增或改动 `custom-tab-bar` 目录后要重启 `dev:mp-weixin`，增量构建不会拷贝它。
  - **AI 入口**：没有悬浮球。入口是首页搜索框的「问 AI」、各详情页的问题条和法官房的「速查」，统一走 `utils/assistant-entry.js` 的 `openAssistant(scene, context, question)`（带 question 会自动发问）。回答下方按提问里提到的角色给一张「出处」角色卡。
  - **世界观**：图鉴页顶部是「夜镇编年史」入口卡，`pages/world/index` 是世界观长页（小镇、三方势力、编年史时间线，时间线头像点进角色详情）。角色详情名字卡显示称号 / 角色名 / 真名（不再显示主题色色块），正文是技能、身世、人物关系（点头像 `redirectTo` 对方详情）。
  - **场景图**：酒馆 / 月夜 / 广场三张场景图打包在 `src/static/scenes/`（去水印裁切版），用作社区横幅、法官房和结果页的兜底背景。
  - tabBar 页面不能带 query：跳社区并带筛选（某板子 / 只看我的）走 `storage.setCommunityFilterContext`，社区页 `onShow` 时取一次。开发环境 API 地址在 `.env.development`（`VITE_API_BASE_URL`，应与后端 8088 端口一致）。

## 部署
生产使用 `docker-compose.prod.yml`（服务：`mysql`、带 pgvector 的 `postgres`、`backend`、`admin`、`gateway`）。环境变量模板：`deploy/.env.prod.example`；pgvector 初始化脚本：`deploy/postgres/init/01-enable-vector.sql`。

**生产服务器现状（124.222.9.209，root 免密登录）**：这台机器和另一个项目 online-safe 共用，80/443 被它的 Caddy 占着，所以实际用的是 `docker-compose.caddy.yml`，不是 `docker-compose.prod.yml`：
- 不起 nginx 网关。`wolfbook-backend` / `wolfbook-admin` 挂到 Caddy 的网络 `online-safe_edge`，Caddy 按 `deploy/caddy/wolfbook.caddy` 反代，这段已追加进 `/opt/online-safe/repo/deploy/Caddyfile`。证书由 Caddy 自动签发（TLS-ALPN，80 端口的 HTTP 会被腾讯云拦截页劫持）。
- **所有服务名都带 `wolfbook-` 前缀**：online-safe 在共享网络里也有 `backend`、`mysql` 别名，撞名会让 Caddy 转错、后端连到对方的 MySQL。
- 机器只有 3.6G 内存（另加了 2G swap），各容器都限了内存；镜像只装运行时，部署步骤是：本地 `./mvnw -DskipTests package` 和 admin `npm run build` → 拷进 `deploy/runtime/`（已 gitignore）→ 上传到 `/opt/wolfbook` → `docker compose -f docker-compose.caddy.yml --env-file deploy/.env.prod up -d --build`。服务器上 `/opt/wolfbook` 不是 git 仓库。
- 2048 维的向量建不了 HNSW 索引（pgvector 上限 2000），`AssistantAiConfiguration` 超限时不建索引。
- 生产库有早期结构遗留，改表或迁数据前先比对两边的列；做过的迁移在 `deploy/sql/`，备份在服务器 `/opt/wolfbook/.deploy-backups/`。
