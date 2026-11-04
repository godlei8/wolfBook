<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiDocument, AdminAiDocumentContent, AdminAiVersion } from '../../types'
import { go } from '../../utils/route'
import {
  aiPath,
  currentVersion,
  formatFullTime,
  formatLogTime,
  groupStructured,
  onlineCount,
  pendingPublish,
  uploadStatus,
  type KnowledgeTab,
  type UploadStatus,
} from '../../utils/aiAdmin'

// 资料库：自动资料（板子 / 角色 / 世界观，改了就生效，只看不审）、上传文档（审核、发布）、发布记录（回滚）
const props = defineProps<{
  tab: KnowledgeTab
  documents: AdminAiDocument[]
  versions: AdminAiVersion[]
  now: Date
}>()
const emit = defineEmits<{ reload: [...what: ('documents' | 'versions')[]] }>()

const GROUP_PREVIEW = 6
const TABS: { key: KnowledgeTab; label: string }[] = [
  { key: 'auto', label: '自动资料' },
  { key: 'uploads', label: '上传文档' },
  { key: 'versions', label: '发布记录' },
]
const STATUS: Record<UploadStatus, { label: string; tone: string }> = {
  parsing: { label: '解析中', tone: '' },
  failed: { label: '解析失败', tone: 'ai-tag--bad' },
  pending: { label: '待审核', tone: 'ai-tag--warn' },
  waiting: { label: '已通过 · 待发布', tone: 'ai-tag--warn' },
  online: { label: '线上', tone: 'ai-tag--ok' },
  rejected: { label: '已驳回', tone: '' },
}

const version = computed(() => currentVersion(props.versions))
const currentId = computed(() => version.value?.id ?? null)
const online = computed(() => onlineCount(props.documents, currentId.value))
const structured = computed(() => groupStructured(props.documents))
const autoTotal = computed(() => structured.value.boards.length + structured.value.roles.length + structured.value.world.length)
const uploads = computed(() => props.documents.filter((doc) => doc.sourceType === 'DOCUMENT'))
const pending = computed(() => pendingPublish(props.documents, currentId.value))
const tabCounts = computed<Record<KnowledgeTab, string>>(() => ({
  auto: String(autoTotal.value),
  uploads: String(uploads.value.length),
  versions: String(props.versions.length),
}))

// ---------- 自动资料 ----------
const search = ref('')
const expanded = reactive<Record<string, boolean>>({})
const selectedId = ref<number | null>(null)
const content = ref<AdminAiDocumentContent | null>(null)
const contentLoading = ref(false)

const groups = computed(() => {
  const needle = search.value.trim()
  const match = (doc: AdminAiDocument) => !needle || doc.name.includes(needle)
  return [
    { key: 'boards', label: '板子', items: structured.value.boards.filter(match) },
    { key: 'roles', label: '角色', items: structured.value.roles.filter(match) },
    { key: 'world', label: '世界观', items: structured.value.world.filter(match) },
  ].filter((group) => group.items.length)
})
const selected = computed(() => props.documents.find((doc) => doc.id === selectedId.value) || null)

function shownItems(group: { key: string; items: AdminAiDocument[] }) {
  // 搜索时全部显示
  return expanded[group.key] || search.value.trim() ? group.items : group.items.slice(0, GROUP_PREVIEW)
}

async function pick(doc: AdminAiDocument) {
  selectedId.value = doc.id
  contentLoading.value = true
  try {
    const loaded = await api.getAiDocumentContent(doc.id)
    // 连点两份时，先发出去的那份晚回来也不能盖掉后点的
    if (selectedId.value === doc.id) content.value = loaded
  } catch (error) {
    if (selectedId.value === doc.id) content.value = null
    ElMessage.error(error instanceof Error ? error.message : '原文加载失败')
  } finally {
    if (selectedId.value === doc.id) contentLoading.value = false
  }
}

// 进来默认看第一份
watch(
  () => [props.tab, structured.value.boards[0]?.id] as const,
  () => {
    if (props.tab !== 'auto' || selectedId.value != null) return
    const first = structured.value.boards[0] || structured.value.roles[0] || structured.value.world[0]
    if (first) void pick(first)
  },
  { immediate: true },
)

function editLink(doc: AdminAiDocument) {
  if (doc.sourceKey.startsWith('BOARD:')) return { label: '去改这个板子 ›', path: `boards/${doc.sourceId}` }
  if (doc.sourceKey.startsWith('ROLE:')) return { label: '去角色图鉴改 ›', path: 'roles' }
  return { label: '去改世界观 ›', path: 'world' }
}

function isOnline(doc: AdminAiDocument) {
  return currentId.value != null && doc.publishVersionId === currentId.value && doc.reviewStatus === 'APPROVED' && doc.processingStatus === 'READY'
}

// ---------- 上传文档 ----------
const busyId = ref<number | null>(null)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const menuOpen = ref(false)

async function review(doc: AdminAiDocument, reviewStatus: 'APPROVED' | 'REJECTED') {
  if (reviewStatus === 'REJECTED' && uploadStatus(doc, currentId.value) === 'online') {
    try {
      await ElMessageBox.confirm(`驳回后「${doc.name}」马上从线上撤下，AI 不会再用它回答。`, '驳回线上文档', {
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  busyId.value = doc.id
  try {
    await api.updateAiDocumentReview(doc.id, reviewStatus)
    emit('reload', 'documents')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审核状态更新失败')
  } finally {
    busyId.value = null
  }
}

async function reindex(doc: AdminAiDocument) {
  busyId.value = doc.id
  try {
    await api.reindexAiDocument(doc.id)
    ElMessage.success('索引已重建')
    emit('reload', 'documents')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '重建索引失败')
  } finally {
    busyId.value = null
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  uploading.value = true
  try {
    await api.uploadAiDocument(file)
    ElMessage.success('已上传，解析完审核通过、再发布就能用了')
    go(aiPath('knowledge', 'uploads'))
    emit('reload', 'documents')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败')
    // 解析失败时后端也留了一条「解析失败」的记录，刷出来好看原因
    emit('reload', 'documents')
  } finally {
    uploading.value = false
  }
}

async function clearUploads() {
  menuOpen.value = false
  try {
    await ElMessageBox.confirm(
      '将清空所有上传的知识文档，并同步移除它们的发布关联。结构化板子和角色知识不会被删除。',
      '清空上传文档',
      { confirmButtonText: '确认清空', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const removed = await api.clearAiDocuments()
    ElMessage.success(removed ? `已清空 ${removed} 份上传文档` : '当前没有可清空的上传文档')
    emit('reload', 'documents', 'versions')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '清空文档失败')
  }
}

// ---------- 发布 / 回滚 ----------
const publishOpen = ref(false)
const publishing = ref(false)
const notes = ref('')
const canPublish = computed(() => !version.value || pending.value.length > 0)
const publishLabel = computed(() => (!version.value ? '首次发布' : pending.value.length ? `发布资料（${pending.value.length} 份待发布）` : '没有待发布的'))

function openPublish() {
  notes.value = ''
  publishOpen.value = true
}

async function publish() {
  publishing.value = true
  try {
    await api.publishAiVersion(notes.value.trim())
    publishOpen.value = false
    ElMessage.success('已发布，新资料现在能被检索到了')
    emit('reload', 'documents', 'versions')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

async function rollback(target: AdminAiVersion) {
  try {
    await ElMessageBox.confirm(
      `上传文档回到「${target.notes || target.versionName}」那一版（${formatFullTime(target.createTime)}）。板子、角色、世界观的资料不受影响，仍是最新的。`,
      '回滚到这一版',
      { confirmButtonText: '回滚', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await api.rollbackAiVersion(target.id)
    ElMessage.success('已回滚')
    emit('reload', 'documents', 'versions')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '回滚失败')
  }
}
</script>

<template>
  <div class="kn">
    <header class="kn-top">
      <div class="kn-title">
        <h1>资料库</h1>
        <span class="kn-muted">{{ version ? `线上 ${online} 份` : '还没发布过' }} · 自动 {{ autoTotal }} · 上传 {{ uploads.length }}</span>
      </div>
      <div class="kn-actions">
        <input ref="fileInput" type="file" class="nt-sr-only" accept=".pdf,.md,.txt" @change="upload" />
        <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="uploading" @click="fileInput?.click()">{{ uploading ? '上传中…' : '上传文档' }}</button>
        <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="!canPublish" @click="openPublish">{{ publishLabel }}</button>
      </div>
    </header>

    <div class="kn-body">
      <nav class="kn-tabs" aria-label="资料库标签">
        <button
          v-for="item in TABS"
          :key="item.key"
          type="button"
          :class="{ 'is-on': tab === item.key }"
          :aria-current="tab === item.key ? 'page' : undefined"
          @click="go(aiPath('knowledge', item.key))"
        >
          {{ item.label }} <small>{{ tabCounts[item.key] }}</small>
        </button>
      </nav>
      <p v-if="!version" class="kn-warn">还没发布过资料，助手现在拿不到任何站内资料。点右上「首次发布」。</p>

      <!-- 自动资料 -->
      <div v-if="tab === 'auto'" class="kn-auto">
        <div class="kn-dir">
          <input v-model="search" class="nt-input kn-search" type="search" placeholder="搜索板子、角色…" aria-label="搜索自动资料" />
          <p class="kn-muted">后台改了板子 / 角色 / 世界观就自动更新，不用发布</p>
          <div v-if="groups.length" class="kn-groups">
            <section v-for="group in groups" :key="group.key" class="kn-group">
              <span class="kn-group__head">{{ group.label }} · {{ group.items.length }}</span>
              <button
                v-for="doc in shownItems(group)"
                :key="doc.id"
                type="button"
                class="kn-doc"
                :class="{ 'is-on': doc.id === selectedId }"
                @click="pick(doc)"
              >
                {{ doc.name }}
              </button>
              <button
                v-if="!search.trim() && group.items.length > GROUP_PREVIEW"
                type="button"
                class="kn-doc kn-doc--more"
                @click="expanded[group.key] = !expanded[group.key]"
              >
                {{ expanded[group.key] ? '收起' : `…还有 ${group.items.length - GROUP_PREVIEW} 个` }}
              </button>
            </section>
          </div>
          <div v-else class="nt-empty">没找到</div>
        </div>
        <section class="kn-view">
          <template v-if="selected">
            <div class="kn-view__head">
              <h2>{{ selected.name }}</h2>
              <span class="ai-tag" :class="isOnline(selected) ? 'ai-tag--ok' : ''">{{ isOnline(selected) ? '线上' : '未上线' }}</span>
              <a class="kn-link" :href="`#/${editLink(selected).path}`">{{ editLink(selected).label }}</a>
            </div>
            <p class="kn-muted">AI 回答时拿到的就是下面这些字 · {{ selected.chunkCount }} 段 · 更新于 {{ formatLogTime(selected.updateTime, now) }}</p>
            <pre v-if="content && content.id === selected.id" class="kn-text">{{ content.contentText || '（空）' }}</pre>
            <div v-else class="kn-muted">{{ contentLoading ? '正在读取原文…' : '点左边一份资料看原文' }}</div>
          </template>
          <div v-else class="nt-empty">左边选一份资料</div>
        </section>
      </div>

      <!-- 上传文档 -->
      <section v-else-if="tab === 'uploads'" class="kn-panel">
        <div class="kn-row">
          <p class="kn-muted">PDF / MD / TXT。新上传的要先通过、再发布才会被检索到；驳回线上文档会马上撤下。</p>
          <div class="kn-menu">
            <button type="button" class="nt-iconbtn kn-menu__btn" aria-label="更多操作" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">⋯</button>
            <div v-if="menuOpen" class="kn-menu__pop">
              <button type="button" :disabled="!uploads.length" @click="clearUploads">清空上传文档</button>
            </div>
          </div>
        </div>
        <ul v-if="uploads.length" class="kn-list">
          <li v-for="doc in uploads" :key="doc.id" class="kn-item">
            <div class="kn-item__name">
              <strong>{{ doc.name }}</strong>
              <span class="kn-muted">{{ doc.fileName || doc.sourceKey }} · {{ doc.chunkCount }} 段 · {{ formatLogTime(doc.updateTime, now) }}</span>
              <span v-if="doc.lastError" class="kn-error">{{ doc.lastError }}</span>
              <span v-if="doc.summary" class="kn-summary">{{ doc.summary }}</span>
            </div>
            <span class="ai-tag" :class="STATUS[uploadStatus(doc, currentId)].tone">{{ STATUS[uploadStatus(doc, currentId)].label }}</span>
            <div class="kn-item__actions">
              <template v-if="uploadStatus(doc, currentId) === 'pending'">
                <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="busyId === doc.id" @click="review(doc, 'APPROVED')">通过</button>
                <button type="button" class="kn-link" :disabled="busyId === doc.id" @click="review(doc, 'REJECTED')">驳回</button>
              </template>
              <button v-else-if="uploadStatus(doc, currentId) === 'rejected'" type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="busyId === doc.id" @click="review(doc, 'APPROVED')">通过</button>
              <template v-else-if="uploadStatus(doc, currentId) !== 'parsing'">
                <button v-if="uploadStatus(doc, currentId) !== 'waiting'" type="button" class="kn-link" :disabled="busyId === doc.id" @click="reindex(doc)">重建索引</button>
                <button v-if="uploadStatus(doc, currentId) !== 'failed'" type="button" class="kn-link" :disabled="busyId === doc.id" @click="review(doc, 'REJECTED')">驳回</button>
              </template>
            </div>
          </li>
        </ul>
        <div v-else class="nt-empty">还没有上传文档。规则、术语这类站里没有的资料可以传上来给 AI 参考。</div>
      </section>

      <!-- 发布记录 -->
      <section v-else class="kn-panel">
        <p class="kn-muted">资料每发布一次记一条。回滚只决定上传文档用哪一批，板子、角色、世界观的资料永远是最新的。</p>
        <ol v-if="versions.length" class="kn-list">
          <li v-for="item in versions" :key="item.id" class="kn-item kn-item--version">
            <div class="kn-item__name">
              <strong>{{ item.notes || item.versionName }}</strong>
              <span class="kn-muted">{{ formatFullTime(item.createTime) }} · {{ item.publishedBy || '—' }} · {{ item.documentIds.length }} 份</span>
            </div>
            <span v-if="item.current" class="ai-tag ai-tag--ok">当前线上</span>
            <button v-else type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="rollback(item)">回滚到这一版</button>
          </li>
        </ol>
        <div v-else class="nt-empty">还没有发布过资料</div>
        <button v-if="version" type="button" class="kn-link kn-republish" @click="openPublish">重新发布当前资料 ›</button>
      </section>
    </div>

    <el-dialog v-model="publishOpen" :title="version ? '发布资料' : '首次发布资料'" width="min(560px, 94vw)" append-to-body>
      <div class="kn-publish">
        <p v-if="pending.length" class="kn-muted">这些上传文档会进到线上：</p>
        <ul v-if="pending.length" class="kn-pending">
          <li v-for="doc in pending" :key="doc.id">{{ doc.name }}</li>
        </ul>
        <p v-else class="kn-muted">把现在所有已通过的资料发布成新的一版。</p>
        <label class="nt-field">
          <span class="nt-label">发布说明 <small>· 可不填</small></span>
          <input v-model="notes" class="nt-input" maxlength="200" placeholder="比如：加了术语表" />
        </label>
      </div>
      <template #footer>
        <div class="kn-foot">
          <button type="button" class="nt-btn nt-btn--ghost" @click="publishOpen = false">取消</button>
          <button type="button" class="nt-btn nt-btn--gold" :disabled="publishing" @click="publish">{{ publishing ? '发布中…' : '确认发布' }}</button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kn {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.kn-top {
  min-height: 64px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--line);
}

.kn-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.kn-title h1 {
  margin: 0;
  font: 900 22px var(--serif);
}

.kn-actions {
  display: flex;
  gap: 8px;
}

.kn-body {
  padding: 16px 24px 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.kn-tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid var(--line);
}

.kn-tabs button {
  padding: 0 2px 10px;
  border: 0;
  background: transparent;
  color: var(--text-2);
  font: 700 14.5px var(--sans);
  cursor: pointer;
}

.kn-tabs button small {
  color: var(--text-3);
  font-weight: 600;
}

.kn-tabs button.is-on {
  color: var(--text);
  box-shadow: inset 0 -3px 0 var(--gold);
}

.kn-warn {
  margin: 0;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--danger-bg);
  color: var(--camp-wolf);
  font-size: 13px;
}

.kn-muted {
  margin: 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.kn-auto {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.kn-dir {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kn-search {
  height: 38px;
  font-size: 14px;
}

.kn-groups {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
  border-radius: 16px;
  background: var(--panel);
}

.kn-group {
  display: flex;
  flex-direction: column;
}

.kn-group__head {
  padding: 8px 10px 4px;
  color: var(--text-3);
  font-size: 12px;
}

.kn-doc {
  padding: 7px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--text);
  font: 14px var(--sans);
  text-align: left;
  cursor: pointer;
}

.kn-doc:hover {
  background: var(--panel-raised);
}

.kn-doc.is-on {
  background: var(--panel-raised);
  box-shadow: inset 3px 0 0 var(--gold);
  font-weight: 700;
}

.kn-doc--more {
  color: var(--moon);
  font-size: 13px;
}

.kn-view {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
  border-radius: 16px;
  background: var(--panel);
  min-height: 300px;
}

.kn-view__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.kn-view__head h2 {
  margin: 0;
  font: 900 19px var(--serif);
}

.kn-view__head .kn-link {
  margin-left: auto;
}

.kn-text {
  margin: 0;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--text-soft);
  font: 13.5px/1.85 var(--sans);
  white-space: pre-wrap;
  word-break: break-word;
}

.kn-link {
  border: 0;
  background: transparent;
  color: var(--moon);
  font: 600 13px var(--sans);
  cursor: pointer;
  padding: 0;
  text-decoration: none;
}

.kn-link:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.kn-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.kn-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.kn-list {
  list-style: none;
  margin: 0;
  padding: 4px 18px;
  border-radius: 16px;
  background: var(--panel);
}

.kn-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(150px, auto);
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-top: 1px solid var(--line);
}

.kn-item:first-child {
  border-top: 0;
}

.kn-item--version {
  grid-template-columns: minmax(0, 1fr) auto;
}

.kn-item__name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kn-item__name strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kn-summary {
  color: var(--text-3);
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kn-error {
  color: var(--camp-wolf);
  font-size: 12.5px;
}

.kn-item__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.kn-republish {
  align-self: flex-start;
}

.kn-menu {
  position: relative;
}

.kn-menu__btn {
  width: 34px;
  height: 34px;
  font-size: 18px;
}

.kn-menu__pop {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: 5;
  min-width: 160px;
  padding: 6px;
  border-radius: 12px;
  background: var(--panel-raised);
  border: 2px solid var(--ink);
  box-shadow: 3px 3px 0 var(--ink);
}

.kn-menu__pop button {
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--camp-wolf);
  font: 600 13px var(--sans);
  text-align: left;
  cursor: pointer;
}

.kn-menu__pop button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.kn-publish {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.kn-pending {
  margin: 0;
  padding: 10px 14px 10px 30px;
  border-radius: 12px;
  background: var(--bg);
}

.kn-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 900px) {
  .kn-auto {
    grid-template-columns: 1fr;
  }

  .kn-item {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .kn-item__actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
}
</style>
