# Wolfbook

狼人杀内容平台 + 运营后台 + 微信小程序 + 站内 AI 助手的一体化项目。

当前仓库已经覆盖：

- 小程序端内容浏览、社区互动、对局笔记、用户中心
- 管理后台板子/角色/社区/AI 助手全链路运营
- Spring Boot 后端接口、微信登录、COS 上传、AI RAG
- 生产环境 HTTPS + Docker Compose 部署骨架

## 在线地址

- 生产域名：[https://wolfbook.godlei8.top](https://wolfbook.godlei8.top)
- 管理后台：[https://wolfbook.godlei8.top/console/](https://wolfbook.godlei8.top/console/)
- API 示例：[https://wolfbook.godlei8.top/api/boards](https://wolfbook.godlei8.top/api/boards)

## 功能总览

### 小程序 `miniprogram-vue`

- `板子`：
  - 板子列表、筛选、搜索、详情
  - 角色阵容、特殊规则、小贴士、FAQ 展示
- `角色`：
  - 角色列表、详情
  - 头像、立绘、技能、背景、FAQ 展示
- `社区`：
  - 发帖、评论、点赞、举报
  - 图片内容展示
- `笔记`：
  - 选择板子库开局
  - 自定义板子开局
  - 对局详情与本地记录
  - 查验、投票、发言等轮次笔记
  - “第几天”样式的日记式记录
- `我的`：
  - 微信登录
  - 微信头像/昵称完善
  - 收藏、本地笔记、设置
- `AI 助手`：
  - 贴边悬浮入口
  - 问答页
  - 狼人杀知识问答
  - 按人数、难度推荐板子
  - 展示站内来源与推荐板子

### 管理后台 `admin`

- 黑金主题控制台
- 管理员登录
- 总览摘要
- 板子管理
  - 基础信息
  - 板子封面上传与预览
  - 板子角色配置
  - 规则、FAQ、小贴士维护
- 角色管理
  - 基础资料维护
  - 头像/立绘上传与预览
  - 阵营、类型、技能、背景维护
- 社区治理
  - 帖子状态管理
  - 评论删除
  - 举报处理
- AI 助手管理
  - 控制台配置
  - 知识库上传
  - 审核、重建索引、发布、回滚
  - 问答日志
  - 清空文档 / 清空日志
  - 分页与滚动优化

### 后端 `backend`

- 板子、角色、社区、用户、管理员接口
- 微信小程序真实登录
- 用户资料同步与保存
- 统一文件上传能力
  - 后台上传
  - 社区图片上传
  - AI 知识库文档上传
- 腾讯云 COS 存储接入
- AI 助手后端能力
  - 对话会话持久化
  - 结构化板子推荐
  - 文档知识库
  - 发布版本管理
  - PGVector 检索
  - MiniMax 聊天与联网搜索
- 生产环境 `prod` 配置
  - Swagger 关闭
  - HTTPS 反代
  - 生产管理员初始化密码保护

## AI 助手能力边界

当前 AI 助手聚焦在站内狼人杀知识场景：

- 狼人杀角色、规则、板型知识问答
- 基于站内板库的推荐
- 基于知识库文档的 RAG 检索回答
- 内部知识不足时使用 MiniMax 联网搜索兜底

不做的能力：

- 局中实时裁判
- 代替法官判罚
- 实时站边裁决
- 胜负判定裁决

### AI 链路

1. 用户提问
2. 意图分类
3. 优先命中结构化板库/角色数据
4. 其次命中已发布知识库 + PGVector
5. 必要时走 MiniMax 联网搜索
6. 返回答案、来源、推荐板子、会话日志

## 技术栈

### 后端

- Java 21
- Spring Boot 3.5
- MyBatis-Plus
- MySQL 8
- PostgreSQL 16 + pgvector
- Spring AI
- MiniMax
- 腾讯云 COS

### 管理后台

- Vue 3
- TypeScript
- Vite
- Element Plus

### 小程序

- uni-app
- Vue 3
- 微信小程序

## 目录结构

```text
backend/             Spring Boot 后端
admin/               Vue 3 管理后台
miniprogram-vue/     uni-app 微信小程序
deploy/              生产环境部署配置模板
docs/                部署文档
scripts/             本地辅助脚本
docker-compose.prod.yml
README.md
狼人杀.md             需求文档
原型图.zip             原型资源
```

## 本地开发

## 环境要求

- Java 21
- Maven Wrapper
- Node.js 20+
- MySQL 8
- 微信开发者工具
- 如需本地跑 AI 检索：PostgreSQL + pgvector

## 1. 初始化数据库

创建 MySQL 数据库：

```sql
CREATE DATABASE wolfbook DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

首次初始化可以手动执行：

- `backend/src/main/resources/schema.sql`

默认配置读取：

- `backend/src/main/resources/application.yml`
- 本地覆盖：`backend/src/main/resources/application-local.yml`
- 开发覆盖：`backend/src/main/resources/application-dev.yml`

说明：

- 当前仓库默认 `SPRING_SQL_INIT_MODE=never`
- 空库启动后会自动写入示例板子、角色、社区内容和管理员账号

本地默认管理员：

- 用户名：`admin`
- 密码：`wolf123`

## 2. 启动后端

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd spring-boot:run
```

开发环境默认端口：

- `http://localhost:8080`

Swagger：

- [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

## 3. 启动管理后台

```powershell
cd D:\Ai\wolfbook\admin
npm install
npm run dev
```

开发环境默认地址：

- [http://localhost:5173](http://localhost:5173)

## 4. 启动微信小程序

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

- `D:\Ai\wolfbook\miniprogram-vue\dist\build\mp-weixin`

## 5. 可选：本地启用 AI 检索

如果要本地完整体验 AI 助手的知识库检索，需要额外准备：

- PostgreSQL
- pgvector 扩展
- MiniMax API Key
- COS 配置

仓库里已提供本地辅助脚本：

- `scripts/start-pgvector.ps1`
- `scripts/stop-pgvector.ps1`

## 微信登录说明

当前项目已经接入微信小程序真实登录链路。

需要配置：

- `WECHAT_MINI_APP_ID`
- `WECHAT_MINI_APP_SECRET`
- 小程序 `miniprogram-vue/src/manifest.json` 中的 `mp-weixin.appid`

体验版 / 正式版还需要在微信公众平台配置合法域名：

- `request`：`https://wolfbook.godlei8.top`
- `uploadFile`：`https://wolfbook.godlei8.top`
- `downloadFile`：`https://cos.godlei8.top`

## 文件上传与存储

项目当前统一使用腾讯云 COS 上传：

- 后台普通资源
- 板子封面
- 角色头像 / 立绘
- 社区图片
- AI 知识库文档

相关配置入口：

- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-prod.yml`

## 生产部署

生产环境已经按单机 Docker Compose 设计完成，核心约定如下：

- 外部入口：`443`
- 后端内部端口：`8110`
- 后台静态服务内部端口：`6110`
- 域名：`https://wolfbook.godlei8.top`

生产部署文档：

- `docs/DEPLOY_PROD.md`

生产相关文件：

- `docker-compose.prod.yml`
- `backend/src/main/resources/application-prod.yml`
- `deploy/nginx/wolfbook.conf`
- `deploy/.env.prod.example`

说明：

- `deploy/.env.prod`、证书和本地密钥都不会进入 Git
- 生产环境强制使用环境变量注入敏感配置
- 生产环境默认关闭 Swagger

## 已验证命令

后端：

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd test
```

后台：

```powershell
cd D:\Ai\wolfbook\admin
npm run build
```

小程序：

```powershell
cd D:\Ai\wolfbook\miniprogram-vue
npm run build:mp-weixin
```

## 当前状态

这版仓库已经不是原型阶段，而是完整的可运行版本，包含：

- 板库、角色库、社区、笔记、小程序用户中心
- AI 助手的前后端与后台运营能力
- 微信登录
- COS 上传
- 生产部署配置

如果你要继续往下推进，后面更适合做的是：

- AI 助手知识库内容运营
- 小程序体验版 / 正式版持续发布
- 日志、监控、备份和运维自动化
