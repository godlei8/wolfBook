<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiDocument, AdminAiDocumentContent, AdminAiVersion } from '../../types'
import { currentVersion, formatFullTime, formatLogTime, groupStructured, pendingPublish, uploadStatus, type UploadStatus } from '../../utils/aiAdmin'

// 资料块：上面是板子 / 角色 / 世界观的自动资料（改了就生效，只看不审），下面是上传文档（审核、发布）。原来的「知识库」和「发布中心」
const props = defineProps<{
  documents: AdminAiDocument[]
  versions: AdminAiVersion[]
  now: Date
}>()
const emit = defineEmits<{ reload: [...what: ('documents' | 'versions')[]] }>()

const PREVIEW = 14
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
const groups = computed(() => {
  const { boards, roles, world } = groupStructured(props.documents)
  return [
    { key: 'boards', label: '板子', items: boards },
    { key: 'roles', label: '角色', items: roles },
    { key: 'world', label: '世界观', items: world },
  ]
})
const structuredTotal = computed(() => groups.value.reduce((sum, group) => sum + group.items.length, 0))
const uploads = computed(() => props.documents.filter((doc) => doc.sourceType === 'DOCUMENT'))
const pending = computed(() => pendingPublish(props.documents, currentId.value))

const expanded = reactive<Record<string, boolean>>({})
const content = ref<AdminAiDocumentContent | null>(null)
const contentLoading = ref<number | null>(null)
const historyOpen = ref(false)
const menuOpen = ref(false)
const notes = ref('')
const publishing = ref(false)
const busyId = ref<number | null>(null)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function shownItems(group: { key: string; items: AdminAiDocument[] }) {
  return expanded[group.key] ? group.items : group.items.slice(0, PREVIEW)
}

async function openContent(doc: AdminAiDocument) {
  if (content.value?.id === doc.id) {
    content.value = null
    return
  }
  contentLoading.value = doc.id
  try {
    content.value = await api.getAiDocumentContent(doc.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '原文加载失败')
  } finally {
    contentLoading.value = null
  }
}

async function publish() {
  publishing.value = true
  try {
    await api.publishAiVersion(notes.value.trim())
    notes.value = ''
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
</script>

<template>
  <div class="kn">
    <div class="kn-top">
      <div class="kn-version">
        <span class="kn-muted">线上版本</span>
        <template v-if="version">
          <span>{{ formatLogTime(version.createTime, props.now) }} 发布</span>
          <span v-if="version.notes" class="kn-notes">「{{ version.notes }}」</span>
        </template>
        <span v-else class="ai-tag ai-tag--bad">还没发布过</span>
      </div>
      <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen">
        {{ historyOpen ? '收起历史版本' : '历史版本 / 回滚' }}
      </button>
    </div>

    <div v-if="!version" class="kn-bar kn-bar--bad">
      <span>还没发布过，助手现在拿不到任何站内资料</span>
      <input v-model="notes" class="nt-input kn-bar__notes" maxlength="200" placeholder="发布说明（可不填）" />
      <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="publishing" @click="publish">{{ publishing ? '发布中…' : '发布' }}</button>
    </div>

    <section v-if="historyOpen" class="nt-panel kn-history" aria-label="历史版本">
      <div class="kn-history__head">
        <h3 class="kn-h3">历史版本</h3>
        <div class="kn-history__republish">
          <input v-model="notes" class="nt-input kn-bar__notes" maxlength="200" placeholder="发布说明（可不填）" />
          <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="publishing" @click="publish">
            {{ publishing ? '发布中…' : '重新发布当前资料' }}
          </button>
        </div>
      </div>
      <ol v-if="versions.length" class="kn-versions">
        <li v-for="item in versions" :key="item.id" class="kn-version-row">
          <div>
            <strong>{{ item.notes || item.versionName }}</strong>
            <div class="kn-muted">{{ formatFullTime(item.createTime) }} · {{ item.publishedBy || '—' }} · {{ item.documentIds.length }} 份</div>
          </div>
          <span v-if="item.current" class="ai-tag ai-tag--ok">当前线上</span>
          <button v-else type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="rollback(item)">回滚到这一版</button>
        </li>
      </ol>
      <div v-else class="kn-muted">还没有发布过任何版本</div>
    </section>

    <section class="nt-panel">
      <div class="kn-head">
        <h3 class="kn-h3">自动资料 <small>· 后台改了板子 / 角色 / 世界观就自动更新，不用发布</small></h3>
        <span class="kn-muted">共 {{ structuredTotal }} 份</span>
      </div>
      <div class="kn-groups">
        <div v-for="group in groups" :key="group.key" class="kn-group">
          <div class="kn-group__head">
            <strong>{{ group.label }}</strong>
            <span class="kn-muted">{{ group.items.length }}</span>
          </div>
          <div v-if="group.items.length" class="kn-items">
            <button
              v-for="doc in shownItems(group)"
              :key="doc.id"
              type="button"
              class="kn-item"
              :class="{ 'is-on': content?.id === doc.id }"
              :disabled="contentLoading === doc.id"
              @click="openContent(doc)"
            >
              {{ doc.name }}
            </button>
            <button v-if="group.items.length > PREVIEW" type="button" class="kn-item kn-item--more" @click="expanded[group.key] = !expanded[group.key]">
              {{ expanded[group.key] ? '收起' : `全部 ${group.items.length}` }}
            </button>
          </div>
          <div v-else class="kn-muted">还没有</div>
        </div>
      </div>
      <div v-if="content" class="kn-content">
        <div class="kn-content__head">
          <span class="kn-muted">{{ content.name }} · AI 看到的原文</span>
          <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="content = null">收起</button>
        </div>
        <pre class="kn-content__body">{{ content.contentText || '（空）' }}</pre>
      </div>
    </section>

    <section class="nt-panel">
      <div class="kn-head">
        <h3 class="kn-h3">上传文档 <small>· PDF / MD / TXT，新上传的要先通过、再发布才会被检索到</small></h3>
        <div class="kn-head__actions">
          <input ref="fileInput" type="file" class="nt-sr-only" accept=".pdf,.md,.txt" @change="upload" />
          <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="uploading" @click="fileInput?.click()">
            {{ uploading ? '上传中…' : '上传文档' }}
          </button>
          <div class="kn-menu">
            <button type="button" class="nt-iconbtn kn-menu__btn" aria-label="更多操作" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">⋯</button>
            <div v-if="menuOpen" class="kn-menu__pop">
              <button type="button" :disabled="!uploads.length" @click="clearUploads">清空上传文档</button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="version && pending.length" class="kn-bar">
        <span>{{ pending.length }} 份文档已通过、还没发布</span>
        <input v-model="notes" class="nt-input kn-bar__notes" maxlength="200" placeholder="发布说明（可不填）" />
        <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="publishing" @click="publish">{{ publishing ? '发布中…' : '发布' }}</button>
      </div>

      <ul v-if="uploads.length" class="kn-docs">
        <li v-for="doc in uploads" :key="doc.id" class="kn-doc">
          <div class="kn-doc__name">
            <strong>{{ doc.name }}</strong>
            <span class="kn-muted">{{ doc.fileName || doc.sourceKey }} · {{ doc.chunkCount }} 段 · {{ formatLogTime(doc.updateTime, props.now) }}</span>
            <span v-if="doc.lastError" class="kn-error">{{ doc.lastError }}</span>
            <span v-if="doc.summary" class="kn-summary">{{ doc.summary }}</span>
          </div>
          <span class="ai-tag" :class="STATUS[uploadStatus(doc, currentId)].tone">{{ STATUS[uploadStatus(doc, currentId)].label }}</span>
          <div class="kn-doc__actions">
            <template v-if="uploadStatus(doc, currentId) === 'pending'">
              <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="busyId === doc.id" @click="review(doc, 'APPROVED')">通过</button>
              <button type="button" class="kn-link" :disabled="busyId === doc.id" @click="review(doc, 'REJECTED')">驳回</button>
            </template>
            <template v-else-if="uploadStatus(doc, currentId) === 'rejected'">
              <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="busyId === doc.id" @click="review(doc, 'APPROVED')">通过</button>
            </template>
            <template v-else-if="uploadStatus(doc, currentId) !== 'parsing'">
              <button
                v-if="uploadStatus(doc, currentId) !== 'waiting'"
                type="button"
                class="kn-link"
                :disabled="busyId === doc.id"
                @click="reindex(doc)"
              >
                重建索引
              </button>
              <button v-if="uploadStatus(doc, currentId) !== 'failed'" type="button" class="kn-link" :disabled="busyId === doc.id" @click="review(doc, 'REJECTED')">驳回</button>
            </template>
          </div>
        </li>
      </ul>
      <div v-else class="nt-empty">还没有上传文档。规则、术语这类站里没有的资料可以传上来给 AI 参考。</div>
    </section>
  </div>
</template>

<style scoped>
.kn {
  container-type: inline-size;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.kn-top,
.kn-head,
.kn-history__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.kn-version {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.kn-notes {
  color: var(--text-2);
}

.kn-h3 {
  margin: 0;
  font: 900 17px var(--serif);
}

.kn-h3 small {
  font: 400 13px var(--sans);
  color: var(--text-3);
}

.kn-muted {
  color: var(--text-3);
  font-size: 13px;
}

.kn-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--warn-bg);
  border: 1px solid var(--warn-line);
}

.kn-bar--bad {
  background: var(--danger-bg);
  border-color: #6a2d2d;
}

.kn-bar__notes {
  flex: 1;
  min-width: 180px;
  height: 36px;
  font-size: 14px;
}

.kn-history__republish {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  flex: 1;
  justify-content: flex-end;
}

.kn-versions,
.kn-docs {
  list-style: none;
  margin: 0;
  padding: 0;
}

.kn-version-row,
.kn-doc {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}

.kn-version-row {
  grid-template-columns: minmax(0, 1fr) auto;
}

.kn-version-row:first-child,
.kn-doc:first-child {
  border-top: 0;
}

.kn-groups {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.kn-group {
  padding: 14px;
  border-radius: 14px;
  background: var(--bg);
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.kn-group__head {
  display: flex;
  justify-content: space-between;
}

.kn-items {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.kn-item {
  padding: 2px 9px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: var(--panel-raised);
  color: var(--text-2);
  font: 13px var(--sans);
  cursor: pointer;
}

.kn-item:hover:not(:disabled) {
  color: var(--text);
}

.kn-item.is-on {
  border-color: var(--moon);
  color: var(--text);
}

.kn-item--more {
  background: transparent;
  border: 1px dashed var(--line);
  color: var(--moon);
}

.kn-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kn-content__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.kn-content__body {
  margin: 0;
  max-height: 360px;
  overflow: auto;
  padding: 14px 16px;
  border-radius: 14px;
  background: var(--panel-raised);
  color: var(--text-soft);
  font: 13.5px/1.8 var(--sans);
  white-space: pre-wrap;
  word-break: break-word;
}

.kn-head__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kn-doc__name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kn-doc__name strong {
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

.kn-doc__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  min-width: 150px;
}

.kn-link {
  border: 0;
  background: transparent;
  color: var(--text-2);
  font: 600 13px var(--sans);
  cursor: pointer;
  padding: 0;
}

.kn-link:hover:not(:disabled) {
  color: var(--moon);
}

.kn-link:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.kn-menu {
  position: relative;
}

.kn-menu__btn {
  width: 32px;
  height: 32px;
  font-size: 18px;
}

.kn-menu__pop {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: 5;
  min-width: 150px;
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

.kn-menu__pop button:hover:not(:disabled) {
  background: var(--danger-bg);
}

.kn-menu__pop button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

@container (max-width: 760px) {
  .kn-groups {
    grid-template-columns: 1fr;
  }

  .kn-doc {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .kn-doc__actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
}
</style>
