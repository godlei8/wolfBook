# Wolfbook

狼人杀内容平台，包含：

- `backend`：Spring Boot 后端
- `admin`：Vue 3 管理后台
- `miniprogram-vue`：uni-app 微信小程序

## 当前 AI 架构

项目现在使用的是混合 AI 架构：

- 主回答模型：`DeepSeek / deepseek-v4-flash`
- 向量模型：火山方舟 Embedding
- 向量存储：PostgreSQL `pgvector` 扩展
- 联网搜索：火山方舟 `web_search`

能力链路：

1. 结构化板子推荐优先命中站内数据
2. 知识库问题优先走 `pgvector` 语义检索
3. 检索不足时可走火山联网搜索兜底
4. 最终回答仍由现有助手响应结构返回

## 后端关键配置

主配置文件：

- [backend/src/main/resources/application.yml](/d:/AI/wolfBook/backend/src/main/resources/application.yml:1)
- [backend/src/main/resources/application-prod.yml](/d:/AI/wolfBook/backend/src/main/resources/application-prod.yml:1)

当前配置项分为三组：

### DeepSeek

```yaml
wolfbook:
  assistant:
    deep-seek:
      base-url: https://api.deepseek.com
      model: deepseek-v4-flash
      default-api-key: ${DEEPSEEK_API_KEY}
```

### 火山方舟

```yaml
wolfbook:
  assistant:
    volcengine:
      base-url: https://ark.cn-beijing.volces.com/api/v3
      embedding-model: doubao-embedding-large-text-250515
      embedding-dimensions: 2048
      embedding-api-key: ${VOLCENGINE_EMBEDDING_API_KEY}
      search-model: doubao-seed-1-6-thinking-250715
      search-api-key: ${VOLCENGINE_SEARCH_API_KEY}
```

### pgvector

```yaml
wolfbook:
  assistant:
    pg-vector:
      enabled: true
      url: jdbc:postgresql://postgres:5432/wolfbook_ai
      username: wolfbook_ai
      password: replace-me
      table-name: assistant_vector_store
      schema-name: public
```

## 生产部署

生产部署入口：

- [docker-compose.prod.yml](/d:/AI/wolfBook/docker-compose.prod.yml:1)
- [deploy/.env.prod.example](/d:/AI/wolfBook/deploy/.env.prod.example:1)

生产 compose 当前包含：

- `mysql`
- `postgres`（带 `pgvector` 扩展）
- `backend`
- `admin`
- `gateway`

PostgreSQL 初始化脚本：

- [deploy/postgres/init/01-enable-vector.sql](/d:/AI/wolfBook/deploy/postgres/init/01-enable-vector.sql:1)

## 本地开发

### 启动后端测试

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-backend.ps1
```

### 启动后端开发服务

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend-dev.ps1
```

### 启动管理后台

```powershell
cd .\admin
npm install
npm run dev
```

### 启动小程序构建

```powershell
cd .\miniprogram-vue
npm install
npm run build:mp-weixin
```

## 已验证

- 后端测试：`7` 个通过
- 管理后台构建：通过

## 说明

后台管理端现在仍然管理：

- AI 是否启用
- Prompt
- 检索参数
- 联网搜索开关
- DeepSeek API Key

火山方舟的向量与联网搜索 Key 保持在后端配置中，不从后台下发。
