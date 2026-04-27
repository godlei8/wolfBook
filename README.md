# Wolfbook

狼人杀内容平台，包含 Spring Boot 后端、Vue 管理后台、uni-app 微信小程序和生产部署配置。

旧版 AI 助手后端、后台管理、知识库表、向量库依赖已经移除。小程序端暂时保留 AI 助手入口和聊天页视觉样式，方便后续重新开发新版 AI 助手。

## Modules

- `backend`: Spring Boot API, MyBatis-Plus, MySQL, Redis, Meilisearch.
- `admin`: Vue 3 + Element Plus operations console.
- `miniprogram-vue`: uni-app WeChat mini program.
- `deploy`: Nginx, Docker Compose, certificates, environment template.
- `docs`: SQL and project documentation.

## Core Features

- Board and role library management.
- Community posts, comments, likes, favorites, reports, featured and pinned content.
- User note sessions and structured game records.
- Community search and related recommendations through Meilisearch.
- Admin console for boards, roles and community operations.
- Mini program AI assistant shell retained as a frontend placeholder only.

## Tech Stack

- Java 21
- Spring Boot 3.5
- MyBatis-Plus
- MySQL 8
- Redis 7
- Meilisearch 1.12
- Tencent COS
- Vue 3
- TypeScript
- Vite
- Element Plus
- uni-app

## Local Development

### Backend

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd spring-boot:run
```

Default URLs:

- API: `http://localhost:8080`
- Swagger: `http://localhost:8080/swagger-ui.html`

### Admin

```powershell
cd D:\Ai\wolfbook\admin
npm install
npm run dev
```

Default URL: `http://localhost:5173`

### Mini Program

```powershell
cd D:\Ai\wolfbook\miniprogram-vue
npm install
npm run dev:mp-weixin
```

Production build:

```powershell
cd D:\Ai\wolfbook\miniprogram-vue
npm run build:mp-weixin
```

Then import one of these directories in WeChat Developer Tools:

- `D:\Ai\wolfbook\miniprogram-vue\dist\dev\mp-weixin`
- `D:\Ai\wolfbook\miniprogram-vue\dist\build\mp-weixin`

## Database

Create the MySQL database:

```sql
CREATE DATABASE wolfbook DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Schema files:

- `backend/src/main/resources/schema.sql`
- `backend/src/test/resources/schema-h2.sql`
- `docs/sql/schema.sql`

Runtime schema compatibility is handled by the existing user data and community schema initializers.

## Verification

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd clean test
```

```powershell
cd D:\Ai\wolfbook\admin
npm run build
```

```powershell
cd D:\Ai\wolfbook\miniprogram-vue
npm run build:mp-weixin
```

## Production

Copy the environment template and fill real values:

```powershell
Copy-Item .\deploy\.env.prod.example .\.env.prod
```

Start the production stack:

```powershell
docker compose -f .\docker-compose.prod.yml --env-file .\.env.prod up -d --build
```

Services:

- `gateway`: Nginx HTTPS reverse proxy.
- `admin`: Admin static site.
- `backend`: API service.
- `mysql`: Main business database.
- `redis`: Community hot feed and cache.
- `meilisearch`: Community search and related recommendation index.

## AI Assistant Reset

The old AI assistant implementation has intentionally been removed from backend and admin:

- No `/api/assistant/*` endpoints.
- No `/admin/ai/*` endpoints.
- No `assistant_*` MySQL tables.
- No Spring AI, MiniMax, Ollama or pgvector runtime dependency.
- No pgvector service in production Docker Compose.

The mini program keeps the assistant dock and chat page styling as a placeholder. Rebuild the new AI assistant from a clean backend API and schema when ready.
