# Wolfbook

狼人杀内容平台、社区、笔记本、AI 助手与运营后台的一体化项目。

当前仓库包含 4 个主要部分：

- `backend`：Spring Boot API、数据模型、社区/笔记本/AI 服务
- `admin`：Vue 3 + Element Plus 运营后台
- `miniprogram-vue`：uni-app 微信小程序
- `deploy`：生产环境 Nginx / Docker Compose / 环境变量模板

## 最近更新

### 社区模块 2.0

- 社区内容流升级为 `推荐 / 最新 / 热门` 三类入口，并支持按帖子类型、板子、关键词筛选。
- 后端采用 `MySQL + Redis + Meilisearch` 的组合路线：
  - MySQL 作为社区帖子、评论、收藏、举报的主数据源
  - Redis 维护热度流缓存，按浏览、点赞、评论、收藏、精选、置顶和时间衰减计算热度
  - Meilisearch 承担社区全文搜索、相关推荐和 typo tolerance 搜索兜底
- 社区发帖支持结构化字段：帖子类型、关联板子、摘要、标签、图片。
- 兼容旧数据：会自动回填旧帖子标题/摘要，避免出现 `Untitled post` 这类占位文案。
- 后台社区治理页重做为更偏运营视角的内容卡片布局，适合处理状态、精选、置顶和删除。

### 笔记本 V2

- 对局详情页重构为工作台式布局：头部卡片、战况摘要、总览、座位、时间线、复盘。
- 记录器支持结构化记录类型：
  - `night`
  - `speech`
  - `vote`
  - `identity`
  - `note`
- 新增座位快捷操作、底部记录入口、记录编辑抽屉、时间线卡片、复盘面板。
- 用户数据结构拆分为 `user_note_sessions` + `user_note_records`，并带有自动建表/补列逻辑。
- 历史记录中的 `knife / poison / vote / skill / other` 已统一本地化为中文显示。

### 小程序体验统一

- 统一了微信小程序端主按钮样式，按“微信登录按钮”这一套视觉语言收口。
- 社区首页筛选、发帖页帖子类型、搜索按钮、详情操作按钮等都接入全局按钮体系。
- 输入框高度、行高和多行文本区样式做了全局修复，减少文字被遮挡或裁切的问题。

### 生产环境补齐

- `docker-compose.prod.yml` 现在包含：
  - `nginx`
  - `admin`
  - `backend`
  - `mysql`
  - `redis`
  - `meilisearch`
  - `postgres`（pgvector）
  - `ollama`
- `.env.prod.example` 已补齐社区搜索、AI 向量检索、COS、微信登录等配置项。

## 技术栈

### 后端

- Java 21
- Spring Boot 3.5
- MyBatis-Plus
- MySQL 8
- Redis 7
- Meilisearch 1.12
- PostgreSQL 16 + pgvector
- Spring AI
- Ollama
- MiniMax
- 腾讯云 COS

### 管理后台

- Vue 3
- TypeScript
- Vite
- Element Plus

### 微信小程序

- uni-app
- Vue 3
- 微信小程序运行时

## 当前核心能力

### 社区

- 帖子流：推荐、最新、热门
- 搜索：关键词、板子、帖子类型、排序
- 互动：点赞、评论、收藏、举报
- 推荐：相关推荐、热门板子话题
- 后台治理：发布状态、精选、置顶、删除、评论管理、举报处理

### 笔记本

- 从板子库开局或自定义板子开局
- 结构化记录夜间行动、发言、投票、身份变化和备注
- 对局详情页按总览 / 座位 / 时间线 / 复盘组织信息
- 用户收藏板子、本地/云端笔记同步

### AI 助手

- 站内狼人杀问答
- 板子推荐
- 知识库文档检索
- PGVector 向量召回 + MiniMax / Ollama 能力接入

### 运营后台

- 板子管理
- 角色管理
- 社区治理
- AI 助手配置、文档、发布与日志管理

## 数据与搜索架构

### 社区链路

1. 社区帖子、评论、收藏、举报落在 MySQL。
2. 每次帖子互动和运营状态变化都会刷新热度分数。
3. 热门流优先读取 Redis ZSet 排名。
4. 搜索和相关推荐优先走 Meilisearch。
5. Redis 或 Meilisearch 不可用时，后端仍会保留 MySQL 基础路径，避免核心接口完全不可用。

### 笔记本链路

- `user_note_sessions` 负责对局元信息、玩家状态、总结摘要。
- `user_note_records` 负责每条结构化记录。
- `UserDataSchemaInitializer` 会自动建表、补列和补索引，便于旧库平滑升级。

## 目录结构

```text
backend/             Spring Boot 后端
admin/               Vue 3 管理后台
miniprogram-vue/     uni-app 微信小程序
deploy/              Nginx / 证书 / 环境变量 / PostgreSQL 初始化脚本
docs/                部署文档
scripts/             本地辅助脚本
docker-compose.prod.yml
README.md
```

## 本地开发

### 环境要求

- Java 21
- Maven Wrapper
- Node.js 20+
- MySQL 8
- 微信开发者工具
- 推荐同时准备：
  - Redis 7
  - Meilisearch 1.12
- 如需完整体验 AI 检索：
  - PostgreSQL 16 + pgvector
  - Ollama
  - MiniMax API Key

### 1. 初始化数据库

创建 MySQL 数据库：

```sql
CREATE DATABASE wolfbook DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

项目内置了完整初始表结构：

- `backend/src/main/resources/schema.sql`
- `backend/src/test/resources/schema-h2.sql`

说明：

- `schema.sql` 已包含社区、笔记本、AI 助手等当前表结构。
- 运行期还会通过 `UserDataSchemaInitializer` 和 `CommunitySchemaInitializer` 自动补齐用户数据与社区相关的新增列和索引。
- 默认会写入示例板子、角色、社区数据和管理员账号。

本地默认管理员：

- 用户名：`admin`
- 密码：`wolf123`

### 2. 后端启动

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd spring-boot:run
```

默认地址：

- API：`http://localhost:8080`
- Swagger：`http://localhost:8080/swagger-ui.html`

默认配置文件：

- `backend/src/main/resources/application.yml`
- 可通过 `application-local.yml` 做本地覆盖

### 3. 管理后台启动

```powershell
cd D:\Ai\wolfbook\admin
npm install
npm run dev
```

默认地址：

- `http://localhost:5173`

### 4. 微信小程序启动

```powershell
cd D:\Ai\wolfbook\miniprogram-vue
npm install
npm run dev:mp-weixin
```

生产构建：

```powershell
cd D:\Ai\wolfbook\miniprogram-vue
npm run build:mp-weixin
```

然后在微信开发者工具导入：

- `D:\Ai\wolfbook\miniprogram-vue\dist\dev\mp-weixin`：开发模式
- `D:\Ai\wolfbook\miniprogram-vue\dist\build\mp-weixin`：生产构建

### 5. 推荐的本地基础服务

社区模块推荐在本地同时启用下面这套服务：

- MySQL：主数据
- Redis：热门流 / 热度缓存
- Meilisearch：社区搜索 / typo tolerance / 相关推荐

AI 助手完整模式推荐同时启用：

- PostgreSQL + pgvector
- Ollama

如果只启动 MySQL，项目大部分基础链路仍可开发，但社区搜索和热门流体验会退化。

## 构建与校验

### 后端

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd clean test
.\mvnw.cmd -DskipTests package
```

### 管理后台

```powershell
cd D:\Ai\wolfbook\admin
npm run build
```

### 微信小程序

```powershell
cd D:\Ai\wolfbook\miniprogram-vue
npm run build:mp-weixin
npm run dev:mp-weixin
```

说明：

- `dev:mp-weixin` 是监听模式，确认首轮编译成功后即可停止。
- 当前构建可能会出现 Sass `legacy-js-api` deprecation warning，但不会阻塞运行。

## 生产部署

### 1. 准备环境变量

复制模板并按实际环境填写：

```powershell
Copy-Item .\deploy\.env.prod.example .\.env.prod
```

重点配置：

- MySQL：`MYSQL_*`
- Redis：`REDIS_*`
- Meilisearch：`COMMUNITY_MEILI_*`
- PGVector：`PGVECTOR_*`、`ASSISTANT_PGVECTOR_*`
- MiniMax / Ollama
- COS
- 微信小程序登录
- 管理员初始化密码：`ADMIN_INIT_PASSWORD`

### 2. 准备 HTTPS 证书

Nginx 配置默认读取：

- `deploy/certs/fullchain.pem`
- `deploy/certs/privkey.pem`

### 3. 启动生产栈

```powershell
docker compose -f .\docker-compose.prod.yml --env-file .\.env.prod up -d --build
```

### 4. 服务说明

- `gateway`：统一入口、HTTPS 反代
- `admin`：后台静态站点
- `backend`：API 服务
- `mysql`：业务主库
- `redis`：社区热度缓存
- `meilisearch`：社区搜索
- `postgres`：AI 向量库
- `ollama`：本地 embedding / 模型服务

## 额外说明

- 仓库里有多份设计文档用于需求沉淀，但以当前代码实现与本 README 为准。
- 生产环境首次启动前请确认 Docker、证书目录和 `.env.prod` 均已准备好。
- 社区与笔记本的表结构已经进入自动迁移阶段，升级旧库时建议先备份 MySQL。
