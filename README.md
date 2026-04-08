# Wolfbook

狼人杀全链路项目，包含：

- `backend`：Spring Boot 3 + MyBatis-Plus + MySQL 8
- `admin`：Vue 3 + Element Plus 管理后台
- `miniprogram-vue`：uni-app + Vue 3 微信小程序

## 目录结构

```text
backend/            Spring Boot 后端
admin/              Web 管理后台
miniprogram-vue/    uni-app 微信小程序
docs/sql/schema.sql 数据库结构基线
狼人杀.md            需求文档
原型图.zip           原型图资源
```

## 运行要求

- Java 21
- Node.js 20+
- MySQL 8
- 微信开发者工具

## 1. 初始化数据库

创建数据库：

```sql
CREATE DATABASE wolfbook DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

后端默认会在启动时自动执行 `backend/src/main/resources/schema.sql` 初始化表结构，并在空库时自动写入种子数据。

默认数据库连接：

- `DB_URL=jdbc:mysql://localhost:3306/wolfbook?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false`
- `DB_USERNAME=root`
- `DB_PASSWORD=aa642353`

当前默认关闭自动执行 `schema.sql`，避免和你已经创建好的表重复冲突。
如果你需要重新自动建表，可以临时设置 `SPRING_SQL_INIT_MODE=always`。
如果本地账号密码不同，启动前自行覆盖环境变量即可。

## 2. 启动后端

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd spring-boot:run
```

启动后可访问：

- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

## 3. 启动管理后台

```powershell
cd D:\Ai\wolfbook\admin
npm install
npm run dev
```

默认通过 `http://localhost:8080` 连接后端。

默认管理员账号：

- 用户名：`admin`
- 密码：`wolf123`

## 4. 启动微信小程序

开发模式：

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

然后在微信开发者工具中导入：

- `D:\Ai\wolfbook\miniprogram-vue\dist\build\mp-weixin`

## 5. 当前实现范围

### 后端

- 板子列表 / 详情
- 角色列表 / 详情
- Mock 优先的微信登录、用户资料
- 社区帖子 / 评论 / 点赞 / 举报
- 管理端登录
- 板子 CRUD
- 角色 CRUD
- 帖子上下架 / 删除
- 评论删除
- 举报处理
- 本地文件上传

### 管理后台

- 黑金风格运营后台
- 总览摘要
- 板子编辑、角色分配、FAQ、规则、小贴士维护
- 固定角色类型维护：`平民 / 神职 / 狼人 / 功能狼 / 第三方`
- 社区帖子、评论、举报治理

### 小程序

- `板子 / 社区 / 笔记 / 我的` 四个 Tab
- 板子列表、详情、角色列表、角色详情
- 社区发帖、评论、点赞、举报
- 本地收藏
- 本地笔记本
- 普通投票 / 平票 / 弃票 / 警长归票模板

## 6. 已验证命令

```powershell
cd D:\Ai\wolfbook\backend
.\mvnw.cmd test

cd D:\Ai\wolfbook\admin
npm run build

cd D:\Ai\wolfbook\miniprogram-vue
npm run build:mp-weixin
```

## 7. 注意事项

- 笔记与收藏仍然是本地存储，不回写后端。
- 登录、内容安全、对象存储目前是 mock-first 适配层，后续可无痛替换为真实服务。
- 微信开发者工具真机样式和交互，仍建议再做一轮人工联调。
