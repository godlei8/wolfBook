# AI 助手后台重做 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把后台「AI 助手」页从 6 个 Element Plus 标签改成「状态条 + 问答 / 资料 / 设置」三块的夜镇风格页面，并补两处后端。

**Architecture:** 页面判断全部放进 `admin/src/utils/aiAdmin.ts` 的纯函数（Node 测试运行器单测），组件只管取数和渲染。`AiAssistantManagement.vue` 负责加载、状态条、三块切换和路由；三块各一个组件。后端加看原文接口、改回滚。

**Tech Stack:** Vue 3 + TypeScript + Vite（admin），全局 `nt-*` 样式；Spring Boot + MyBatis-Plus（backend），MockMvc + H2 测试。

**Spec:** `docs/superpowers/specs/2026-11-03-ai-admin-redesign-design.md`（样稿 `.superpowers/brainstorm/1404-1793690238/content/layout-a2.html`）

## Global Constraints

- 用 `style.css` 的 `nt-*` 类和颜色变量，不写死色值；不再用 `el-tabs / el-table / el-card / el-form / el-radio-group / el-upload / el-pagination / el-tag / el-input-number / el-switch`；`ElMessage`、`ElMessageBox`、`el-dialog`、`v-loading` 可留。
- 打码的 Key（`********` + 后 4 位）原样传回表示不改，前端不得改写。
- 现有能力一个不少（spec 文末「能力对照」表）。
- 界面文案中文；时间：列表「今天 17:49」/「10-28 17:49」/「2025-10-28」，详情到秒。
- 后端时间是不带时区的 `LocalDateTime` 字符串（如 `2026-10-28T17:49:47`），按浏览器本地时间解析。
- 慢 = `latencyMs >= 10000`；婉拒 = `answerType === 'REFUSAL'`。
- 在 `feature/ai-admin-redesign` 分支上做，只在用户要求时提交。

## Review Focus

1. 一个设置小节保存时，另一小节没保存的草稿不能被冲掉、也不能被一起提交 → Task 2 `mergeSectionKeepsOtherFieldsFromServer`。
2. 打码 Key 原样回传 → Task 2 `mergeSectionPassesMaskedKeysThrough`。
3. 新库没有任何发布版本时，线上数为 0、页面提示发布 → Task 2 `noCurrentVersionMeansNothingOnline`。
4. 驳回一份线上文档后状态应是「已驳回」而不是「线上」，再通过且版本号仍是当前版本则回到「线上」 → Task 2 `uploadStatusFollowsReviewAndVersion`。
5. 跨午夜的日志（昨天 23:59 / 今天 00:01）计数和时间显示正确 → Task 2 `todayStatsUseLocalDate`。

---

### Task 1: 后端——看原文接口、回滚不动自动资料

**Files:**
- Modify: `backend/src/main/java/com/wolfbook/backend/dto/AssistantDtos.java`（加 `AdminDocumentContentView`）
- Modify: `backend/src/main/java/com/wolfbook/backend/service/assistant/AssistantKnowledgeService.java`（加 `getDocumentContent(Integer id)`）
- Modify: `backend/src/main/java/com/wolfbook/backend/controller/admin/AdminAiController.java`（`GET /documents/{id}`）
- Modify: `backend/src/main/java/com/wolfbook/backend/service/assistant/AssistantPublishService.java:86-107`（`rollback`）
- Test: `backend/src/test/java/com/wolfbook/backend/BackendApplicationTests.java`

**Interfaces:**
- Produces: `GET /admin/ai/documents/{id}` → `ApiResponse<AdminDocumentContentView>`，`record AdminDocumentContentView(Integer id, String name, String sourceType, String sourceKey, String contentText)`；不存在抛 `ApiException(4004, "资料不存在")`。

- [ ] **Step 1: 写失败测试** `assistantDocumentContentIsReadableFromAdmin`：无 Authorization → `$.code` 4001；`GET /admin/ai/documents/999999` → 4004；先 `GET /admin/ai/documents` 拿到 `sourceKey` 为 `BOARD:2` 的那条 id，再 `GET /admin/ai/documents/{id}` → code 0、`$.data.sourceType` = `STRUCTURED`、`$.data.contentText` 含 `预女猎白`。
- [ ] **Step 2: 写失败测试** `rollbackKeepsStructuredKnowledgeOnTheRolledBackVersion`：`POST /admin/ai/publish`（notes `v1`）拿 v1 id → 用 `AssistantDocumentMapper` 插一条 `sourceType=STRUCTURED`、`sourceKey=ROLE:999999`、`APPROVED`、`READY`、`publishVersionId=null` 的文档（模拟发布后新建的角色）→ 再发布 `v2` → `POST /admin/ai/publish/rollback {versionId: v1}` → 查这条文档的 `publishVersionId` 等于 v1 id；同时任一上传文档不受影响（本用例可不建上传文档）。结束时删掉插入的行。
- [ ] **Step 3: 跑** `cd backend && JAVA_HOME=/d/Kf/jdk21 ./mvnw test -Dtest=BackendApplicationTests#assistantDocumentContentIsReadableFromAdmin+rollbackKeepsStructuredKnowledgeOnTheRolledBackVersion` → Expected: 两个都 FAIL（第一个 404/5000，第二个版本号为 null）。
- [ ] **Step 4: 实现**：接口照 `listDocuments` 的写法先 `adminService.requireAdmin`；`rollback` 在写回目标版本 `documentIds` 之后，再把所有 `sourceType = STRUCTURED` 的文档 `publishVersionId` 设为目标 id。
- [ ] **Step 5: 跑同一命令** → PASS；再跑全量 `./mvnw test` → 全部通过。

### Task 2: 纯逻辑 `utils/aiAdmin.ts`

**Files:**
- Create: `admin/src/utils/aiAdmin.ts`
- Test: `admin/tests/ai-admin.test.mjs`

**Interfaces（Produces，后面任务都用这些名字）:**
```ts
export type AiTab = 'qa' | 'knowledge' | 'settings'
export type SettingsSection = 'persona' | 'models' | 'advanced'
export function parseAiPath(parts: string[]): { tab: AiTab; section: SettingsSection }   // ['knowledge'] / ['settings','models'] …，无法识别回落 qa / persona
export function aiPath(tab: AiTab, section?: SettingsSection): string                    // 'ai' | 'ai/knowledge' | 'ai/settings' | 'ai/settings/models'

export function parseLocalTime(value: string): Date
export function formatLogTime(value: string, now: Date): string        // 今天 17:49 / 10-28 17:49 / 2025-10-28
export function formatFullTime(value: string): string                  // 2026-10-28 17:49:47

export type LogFilter = 'all' | 'failed' | 'slow' | 'refused'
export function filterLogs(logs: AdminAiLog[], filter: LogFilter): AdminAiLog[]
export function logFilterCounts(logs: AdminAiLog[]): Record<LogFilter, number>
export function todayStats(logs: AdminAiLog[], now: Date): { total: number; failed: number; slow: number }

export function currentVersion(versions: AdminAiVersion[]): AdminAiVersion | null
export function onlineCount(docs: AdminAiDocument[], currentId: number | null): number
export type UploadStatus = 'parsing' | 'failed' | 'pending' | 'waiting' | 'online' | 'rejected'
export function uploadStatus(doc: AdminAiDocument, currentId: number | null): UploadStatus
export function pendingPublish(docs: AdminAiDocument[], currentId: number | null): AdminAiDocument[]
export function groupStructured(docs: AdminAiDocument[]): { boards: AdminAiDocument[]; roles: AdminAiDocument[]; world: AdminAiDocument[] }
export function hitKind(name: string, docs: AdminAiDocument[]): '板子' | '角色' | '世界观' | '文档' | null

export const PERSONA_FIELDS: string[]   // 'base.enabled','base.welcomeMessage','base.quickQuestions','prompt.systemPrompt','prompt.recommendationPrompt','safety.blockedKeywords','safety.unsupportedMessage'
export const ADVANCED_FIELDS: string[]  // 'retrieval.topK','retrieval.similarityThreshold','retrieval.historyWindow','base.temperature','base.maxSuggestions','search.webSearchEnabled','volcengine.embeddingApiKey','volcengine.searchApiKey'
export function mergeSection(server: AdminAiConfig, draft: AdminAiConfig, fields: string[]): AdminAiConfig   // 深拷贝 server，只把 fields 从 draft 拷过来
export function countChanges(server: AdminAiConfig, draft: AdminAiConfig, fields: string[]): number        // 数组按内容比较
```

- [ ] **Step 1: 写失败测试**（`import ... from '../src/utils/aiAdmin.ts'`），至少这些：
  - `parseAiPathFallsBackToQa`：`[]`→`{tab:'qa',section:'persona'}`；`['settings','models']`→`{tab:'settings',section:'models'}`；`['nope']`→qa；`aiPath('settings','advanced')==='ai/settings/advanced'`、`aiPath('qa')==='ai'`。
  - `formatLogTimeByAge`：now = `new Date(2026,10,3,20,0)`；`'2026-11-03T17:49:47'`→`'今天 17:49'`；`'2026-10-28T09:05:00'`→`'10-28 09:05'`；`'2025-10-28T09:05:00'`→`'2025-10-28'`；`formatFullTime('2026-10-28T17:49:47')==='2026-10-28 17:49:47'`。
  - `todayStatsUseLocalDate`：now = 11-03 00:30；日志 `11-02T23:59:00`（失败）、`11-03T00:01:00`（成功，latency 12000）、`11-03T00:20:00`（成功）→ `{total:2, failed:0, slow:1}`。
  - `filterLogsByKind`：四种筛选各自结果，`logFilterCounts` 与之一致（refused 只认 `REFUSAL`）。
  - `noCurrentVersionMeansNothingOnline`：`currentVersion([])===null`；`onlineCount(docs,null)===0`；已通过的上传文档（版本号 null）出现在 `pendingPublish(docs,null)`。
  - `onlineCountMatchesRetrieval`：current=5；文档 A（v5,APPROVED,READY）、B（v5,REJECTED）、C（v4,APPROVED）、D（v5,APPROVED,FAILED）→ 1。
  - `uploadStatusFollowsReviewAndVersion`：current=5；处理中→`parsing`；`FAILED`→`failed`；`PENDING`→`pending`；`APPROVED`+v4→`waiting`；`APPROVED`+v5→`online`；`REJECTED`+v5→`rejected`。`pendingPublish` 只含 `waiting` 的上传文档，不含结构化资料。
  - `groupStructuredAndHitKind`：`BOARD:2`/`ROLE:3`/`WORLD:1`/上传文档分组正确；`hitKind('预女猎白',docs)==='板子'`、`hitKind('terms_optimized',docs)==='文档'`、未知名→`null`。
  - `mergeSectionKeepsOtherFieldsFromServer`：draft 同时改了 `prompt.systemPrompt` 和 `retrieval.topK`；`mergeSection(server,draft,PERSONA_FIELDS)` 结果 systemPrompt 是新值、topK 是 server 值、不改变 server/draft 原对象；`countChanges(server,draft,PERSONA_FIELDS)===1`、ADVANCED 也是 1；`quickQuestions` 内容相同的新数组不算改动。
  - `mergeSectionPassesMaskedKeysThrough`：draft `volcengine.embeddingApiKey='********ab12'` 合并后原样保留，`provider.apiKey`（不在任何字段表里）取 server 值。
- [ ] **Step 2: 跑** `cd admin && node --test "tests/*.test.mjs"` → Expected: 新文件 FAIL（模块不存在）。
- [ ] **Step 3: 实现** `aiAdmin.ts`（`parseLocalTime` 手动拆 `YYYY-MM-DDTHH:mm:ss` 建本地 Date；字段路径用点号取 / 设值）。
- [ ] **Step 4: 再跑** → 全部 PASS（含原有 board 测试）。

### Task 3: 路由和页面骨架（状态条 + 三块切换）

**Files:**
- Modify: `admin/src/utils/route.ts`（`route` 多给 `aiParts: string[]`，即 `#/ai/` 后面的段）
- Rewrite: `admin/src/components/AiAssistantManagement.vue`
- Modify: `admin/src/services/api.ts`（`getAiDocumentContent(id)`）、`admin/src/types.ts`（`AdminAiDocumentContent`）

**Interfaces:**
- Consumes: Task 2 的 `parseAiPath / aiPath / todayStats / currentVersion / onlineCount / pendingPublish`；Task 1 的接口。
- Produces: 父组件一次加载 `config / models / documents / versions / logs`（`Promise.all`），通过 props 传给三块，三块用 `emit('reload', what?: 'documents'|'versions'|'logs'|'config'|'models')` 请父组件重拉；父组件给 QaPanel 传 `initialFilter`（状态条点「失败」时为 `'failed'`）。三块组件名：`aiAssistant/QaPanel.vue`、`aiAssistant/KnowledgePanel.vue`、`aiAssistant/SettingsPanel.vue`。

- [ ] **Step 1:** 改 `route.ts` 与 `App.vue` 用法保持兼容（`menu === 'ai'` 不变）。
- [ ] **Step 2:** 写骨架：页头、状态条（spec「状态条」一节的四项与口径；点击用 `go(aiPath(...))`）、`nt-seg` 三块切换；先放三个空组件。
- [ ] **Step 3:** `cd admin && npm run build` → Expected: 类型检查通过。

### Task 4: 问答块 `QaPanel.vue`

**Files:** Create `admin/src/components/aiAssistant/QaPanel.vue`；Modify `formatters.ts`（保留 `answerTypeLabel / failureLabel / hitTypeLabel / intentLabel / latencyTone`）。

- [ ] **Step 1:** 实现 spec「1. 问答块」：试答（迁移 `AiPlaygroundTab.vue` 的场景选择、示例问题、`pageContext`、结果展示：判断、资料分数、耗时条、模型）；记录列表（`filterLogs`、`logFilterCounts`、每批 30 条、`formatLogTime`）；右栏记录详情（`formatFullTime`、`renderMarkdown`、`hitKind` 前缀、traceId 复制、「改一下再问」填入并聚焦、「用现在的设置重答」直接试答）；`⋯` 里清空记录（沿用原确认文案，完成后 `emit('reload','logs')`）。
- [ ] **Step 2:** `npm run build` 通过。

### Task 5: 资料块 `KnowledgePanel.vue`

**Files:** Create `admin/src/components/aiAssistant/KnowledgePanel.vue`

- [ ] **Step 1:** 实现 spec「2. 资料块」：顶行线上版本 + 历史版本抽屉（回滚二次确认，抽屉里「重新发布当前资料」）；没有当前版本时的红色提示横条 + 发布；自动资料三组（`groupStructured`），点名字调 `api.getAiDocumentContent` 展开原文；上传文档（原生 file input 上传、`uploadStatus` 决定 chip 和按钮、驳回线上文档确认「驳回后马上从线上撤下」、`⋯` 清空上传文档）；`pendingPublish` 非空时黄色横条 + 说明 + 发布。
- [ ] **Step 2:** `npm run build` 通过。

### Task 6: 设置块 `SettingsPanel.vue` + 模型小节重做

**Files:** Create `admin/src/components/aiAssistant/SettingsPanel.vue`；Rewrite `admin/src/components/aiAssistant/AiModelManager.vue` 的模板和样式（逻辑保留，`el-dialog` 可留）。

- [ ] **Step 1:** 三小节导航（`go(aiPath('settings', s))`）；「话术与人设」「高级」各一份 draft（深拷贝 server config），chip 编辑器（推荐问题、拦截词），保存用 `mergeSection(server, draft, FIELDS)` → `api.saveAiConfig` → 用返回值更新 server 并只重置本小节 draft；`countChanges` 显示「改了 N 处没保存」；有改动时切小节 / 切块 / 离开页面用 `ElMessageBox.confirm` 拦一次（离开页面用 `beforeunload` + hashchange 时检查）；「去试答几题」跳问答块。
- [ ] **Step 2:** 模型小节换成夜镇卡片样式，功能不变。
- [ ] **Step 3:** `npm run build` 通过。

### Task 7: 清理、文档、验证

**Files:** Delete `AiAssistantControlTab.vue`、`AiAssistantDocumentsTab.vue`、`AiAssistantPublishTab.vue`、`AiAssistantLogsTab.vue`、`AiPlaygroundTab.vue`；`formatters.ts` 删掉不再被引用的导出；Modify `CLAUDE.md`、`AGENTS.md`（后台 AI 助手页那句、`#/ai` 路由、后台单测覆盖范围、回滚行为、看原文接口）。

- [ ] **Step 1:** 删除旧组件，`grep -r "AiAssistantControlTab\|AiPlaygroundTab\|el-tabs" admin/src` 无结果。
- [ ] **Step 2:** `cd admin && npm run build && node --test "tests/*.test.mjs"`；`cd backend && ./mvnw test` → 全部通过。
- [ ] **Step 3:** 重启本地后端（带 Task 1 改动），浏览器里走一遍：状态条四项和跳转；试答一题、记录筛选、「改一下再问」；看一份自动资料原文；通过 / 发布 / 回滚（回滚后自动资料仍在线）；话术与人设和高级分别改动、只保存其中一个，另一个草稿还在；模型测试连接。窄屏看一眼不横向溢出。
