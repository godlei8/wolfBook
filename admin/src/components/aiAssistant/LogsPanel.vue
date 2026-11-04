<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiDocument, AdminAiLog } from '../../types'
import { renderMarkdown } from '../../utils/markdown'
import { clampPage, pageNumbers, paginate } from '../../utils/pagination'
import { go } from '../../utils/route'
import {
  SLOW_MS,
  aiPath,
  filterLogs,
  formatFullTime,
  formatLogTime,
  hitKind,
  logFilterCounts,
  neighborId,
  searchLogs,
  sessionThread,
  type LogFilter,
} from '../../utils/aiAdmin'
import { answerTypeLabel, failureLabel } from './formatters'
import { logsRequest, pendingQuestion } from './composeDraft'

// 问答日志：整宽表格，耗时画成横条；点一行从右边滑出抽屉看整段会话，J / K 翻上下条
const props = defineProps<{
  logs: AdminAiLog[]
  documents: AdminAiDocument[]
  now: Date
}>()
const emit = defineEmits<{ reload: [what: 'logs'] }>()

const PAGE_SIZE = 10
const FILTERS: { key: LogFilter; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'failed', label: '失败' },
  { key: 'slow', label: '慢' },
  { key: 'refused', label: '婉拒' },
  { key: 'today', label: '今天' },
]

const filter = ref<LogFilter>('all')
const search = ref('')
const page = ref(1)
const openId = ref<number | null>(null)
const menuOpen = ref(false)
const threadBox = ref<HTMLElement | null>(null)

const counts = computed(() => logFilterCounts(props.logs, props.now))
const filtered = computed(() => searchLogs(filterLogs(props.logs, filter.value, props.now), search.value))
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))
const visible = computed(() => paginate(filtered.value, page.value, PAGE_SIZE))
const pages = computed(() => pageNumbers(page.value, pageCount.value))
const maxLatency = computed(() => Math.max(1, ...visible.value.map((log) => log.latencyMs)))
const opened = computed(() => props.logs.find((log) => log.id === openId.value) || null)
const thread = computed(() => (opened.value ? sessionThread(props.logs, opened.value) : []))
const position = computed(() => (opened.value ? filtered.value.findIndex((log) => log.id === opened.value?.id) : -1))

watch([filter, search], () => {
  page.value = 1
})
watch(
  () => filtered.value.length,
  (total) => {
    page.value = clampPage(page.value, PAGE_SIZE, total)
  },
)

// 数据页跳过来：填搜索框，或者直接打开那一条
watch(
  logsRequest,
  (request) => {
    if (!request) return
    filter.value = 'all'
    search.value = request.search ?? ''
    if (request.logId != null) open(request.logId)
    logsRequest.value = null
  },
  { immediate: true },
)

function open(id: number) {
  openId.value = id
  const index = filtered.value.findIndex((log) => log.id === id)
  if (index >= 0) page.value = Math.floor(index / PAGE_SIZE) + 1
  // 同一会话前面可能有好几问，滚到选中的那一问
  void nextTick(() => {
    const box = threadBox.value
    const current = box?.querySelector('.lg-turn.is-current')
    if (!box || !current) return
    box.scrollTop += current.getBoundingClientRect().top - box.getBoundingClientRect().top - 8
  })
}

function step(delta: 1 | -1) {
  const next = neighborId(
    filtered.value.map((log) => log.id),
    openId.value,
    delta,
  )
  if (next != null) open(next)
}

function onKeydown(event: KeyboardEvent) {
  if (openId.value == null) return
  const target = event.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return
  if (event.ctrlKey || event.metaKey || event.altKey) return
  if (event.key === 'j' || event.key === 'J') step(1)
  else if (event.key === 'k' || event.key === 'K') step(-1)
  else if (event.key === 'Escape') openId.value = null
}

function barWidth(log: AdminAiLog) {
  return `${Math.max(2, Math.round((log.latencyMs / maxLatency.value) * 100))}%`
}

function seconds(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
}

function shortUser(openid: string | null) {
  if (!openid) return '未登录用户'
  return openid.length > 12 ? `${openid.slice(0, 10)}…` : openid
}

function hitLabel(name: string) {
  const kind = hitKind(name, props.documents)
  return kind ? `${kind} · ${name}` : name
}

function debugInCompose(log: AdminAiLog) {
  pendingQuestion.value = log.userMessage
  go(aiPath('compose'))
}

async function copyTrace(traceId: string) {
  try {
    await navigator.clipboard.writeText(traceId)
    ElMessage.success('traceId 已复制')
  } catch {
    ElMessage.warning('复制失败，手动选中复制吧')
  }
}

async function clearLogs() {
  menuOpen.value = false
  try {
    await ElMessageBox.confirm('将清空全部问答日志记录，该操作不可恢复。', '清空问答日志', {
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    const removed = await api.clearAiLogs()
    ElMessage.success(removed ? `已清空 ${removed} 条问答日志` : '当前没有可清空的问答日志')
    openId.value = null
    emit('reload', 'logs')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '清空日志失败')
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="lg">
    <header class="lg-top">
      <div class="lg-title">
        <h1>问答日志</h1>
        <span class="lg-muted">最近 500 条以内，现在 {{ logs.length }} 条</span>
      </div>
      <div class="lg-menu">
        <button type="button" class="nt-iconbtn lg-menu__btn" aria-label="更多操作" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">⋯</button>
        <div v-if="menuOpen" class="lg-menu__pop">
          <button type="button" :disabled="!logs.length" @click="clearLogs">清空问答日志</button>
        </div>
      </div>
    </header>

    <div class="lg-body">
      <div class="lg-filters">
        <button
          v-for="item in FILTERS"
          :key="item.key"
          type="button"
          class="nt-chip lg-chip"
          :class="{ 'nt-chip--on': filter === item.key }"
          @click="filter = item.key"
        >
          {{ item.label }} {{ counts[item.key] }}
        </button>
        <input v-model="search" class="nt-input lg-search" type="search" placeholder="搜索问题…" aria-label="搜索问题" />
      </div>

      <div class="lg-table" role="table" aria-label="问答日志">
        <div class="lg-tr lg-tr--head" role="row">
          <span role="columnheader">时间</span><span role="columnheader">问题</span><span role="columnheader">回答类型</span><span role="columnheader">资料</span><span role="columnheader">耗时</span>
        </div>
        <button
          v-for="log in visible"
          :key="log.id"
          type="button"
          role="row"
          class="lg-tr"
          :class="{ 'is-on': log.id === openId }"
          @click="open(log.id)"
        >
          <span class="lg-muted">{{ formatLogTime(log.createTime, now) }}</span>
          <span class="lg-q">{{ log.userMessage }}</span>
          <span>
            <span v-if="!log.success" class="lg-bad">失败 · {{ failureLabel(log.failureType) || '未知原因' }}</span>
            <span v-else-if="log.answerType === 'REFUSAL'" class="ai-tag">婉拒</span>
            <template v-else>{{ answerTypeLabel(log.answerType) }}</template>
          </span>
          <span>{{ log.hitSources.length || '—' }}</span>
          <span class="lg-lat">
            <i :class="{ 'is-slow': log.latencyMs >= SLOW_MS }" :style="{ width: barWidth(log) }"></i>
            <em :class="{ 'is-slow': log.latencyMs >= SLOW_MS }">{{ seconds(log.latencyMs) }}</em>
          </span>
        </button>
        <div v-if="!visible.length" class="nt-empty lg-empty">{{ logs.length ? '没有符合条件的问答' : '还没有人问过' }}</div>
        <nav v-if="filtered.length > PAGE_SIZE" class="lg-pager" aria-label="翻页">
          <button type="button" :disabled="page === 1" aria-label="上一页" @click="page--">‹</button>
          <template v-for="(item, index) in pages" :key="`${item}-${index}`">
            <span v-if="item === '…'" class="lg-muted">…</span>
            <button v-else type="button" :class="{ 'is-on': item === page }" :aria-current="item === page ? 'page' : undefined" @click="page = item">{{ item }}</button>
          </template>
          <button type="button" :disabled="page === pageCount" aria-label="下一页" @click="page++">›</button>
          <span class="lg-muted">{{ (page - 1) * PAGE_SIZE + 1 }}–{{ Math.min(page * PAGE_SIZE, filtered.length) }} / {{ filtered.length }}</span>
        </nav>
      </div>
    </div>

    <div v-if="opened" class="lg-mask" @click.self="openId = null">
      <aside class="lg-drawer" role="dialog" :aria-label="`${shortUser(opened.openid)} 的对话`">
        <header class="lg-drawer__head">
          <strong>{{ shortUser(opened.openid) }} 的对话</strong>
          <span class="lg-muted">
            <kbd>J</kbd> <kbd>K</kbd> 上下条 · <kbd>Esc</kbd> 关闭
            <template v-if="position >= 0"> · {{ position + 1 }} / {{ filtered.length }}</template>
          </span>
          <button type="button" class="lg-x" aria-label="关闭" @click="openId = null">✕</button>
        </header>
        <div class="lg-muted">
          {{ formatFullTime(opened.createTime) }}
          <template v-if="thread.length > 1"> · 同一会话第 {{ thread.findIndex((item) => item.id === opened?.id) + 1 }} / {{ thread.length }} 问</template>
        </div>
        <div ref="threadBox" class="lg-thread">
          <template v-for="item in thread" :key="item.id">
            <div class="lg-turn" :class="{ 'is-dim': item.id !== opened.id, 'is-current': item.id === opened.id }">
              <div class="lg-bubble lg-bubble--me">{{ item.userMessage }}</div>
              <div v-if="item.answer" class="lg-bubble lg-bubble--ai lg-md" v-html="renderMarkdown(item.answer)"></div>
              <div v-else class="lg-bubble lg-bubble--ai lg-muted">会话已被清空，找不到这次的回答</div>
              <template v-if="item.id === opened.id">
                <div class="lg-meta">
                  <span>{{ answerTypeLabel(item.answerType) }}</span>
                  <span :class="{ 'is-slow': item.latencyMs >= SLOW_MS }">{{ seconds(item.latencyMs) }}{{ item.latencyMs >= SLOW_MS ? ' · 偏慢' : '' }}</span>
                  <span>{{ item.usedWebSearch ? '联网了' : '没联网' }}</span>
                  <span v-if="!item.success" class="lg-bad">失败：{{ failureLabel(item.failureType) || '未知原因' }}</span>
                </div>
                <div class="lg-hits">
                  <span class="lg-label">交给模型的资料</span>
                  <span v-if="!item.hitSources.length" class="lg-muted">没有命中资料</span>
                  <span
                    v-for="name in item.hitSources"
                    :key="name"
                    class="ai-tag"
                    :class="{ 'ai-tag--moon': hitKind(name, documents) && hitKind(name, documents) !== '文档' }"
                  >{{ hitLabel(name) }}</span>
                </div>
              </template>
            </div>
          </template>
        </div>
        <footer class="lg-drawer__foot">
          <button type="button" class="nt-btn nt-btn--gold nt-btn--small" @click="debugInCompose(opened)">拿到编排里调试</button>
          <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="copyTrace(opened.traceId)">复制 traceId</button>
        </footer>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.lg {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.lg-top {
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 24px;
  border-bottom: 1px solid var(--line);
}

.lg-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.lg-title h1 {
  margin: 0;
  font: 900 22px var(--serif);
}

.lg-body {
  padding: 18px 24px 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.lg-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.lg-chip {
  height: 30px;
  font-size: 13px;
}

.lg-search {
  margin-left: auto;
  width: 260px;
  height: 36px;
  font-size: 14px;
}

.lg-table {
  border-radius: 16px;
  overflow: hidden;
  background: var(--panel);
}

.lg-tr {
  width: 100%;
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) 150px 54px minmax(140px, 200px);
  gap: 14px;
  align-items: center;
  min-height: 46px;
  padding: 0 18px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  font: 14px var(--sans);
  text-align: left;
  cursor: pointer;
}

.lg-tr:hover {
  background: var(--panel-raised);
}

.lg-tr.is-on {
  background: var(--warn-bg);
  box-shadow: inset 3px 0 0 var(--gold);
}

.lg-tr--head {
  min-height: 38px;
  background: #1b1724;
  color: var(--text-3);
  font-size: 12.5px;
  font-weight: 700;
  cursor: default;
}

.lg-tr--head:hover {
  background: #1b1724;
}

.lg-q {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lg-lat {
  display: flex;
  align-items: center;
  gap: 8px;
}

.lg-lat i {
  display: block;
  height: 6px;
  max-width: 110px;
  border-radius: 3px;
  background: var(--moon);
}

.lg-lat em {
  font-style: normal;
  color: var(--text-3);
  font-size: 12.5px;
  white-space: nowrap;
}

.lg-lat .is-slow,
.lg-meta .is-slow {
  color: var(--camp-god);
}

.lg-lat i.is-slow {
  background: var(--camp-god);
}

.lg-empty {
  border: 0;
  border-radius: 0;
}

.lg-pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 12px;
}

.lg-pager button {
  min-width: 30px;
  height: 30px;
  padding: 0 8px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--text-2);
  font: 600 13px var(--sans);
  cursor: pointer;
}

.lg-pager button.is-on {
  background: var(--moon);
  border-color: var(--moon);
  color: var(--ink);
}

.lg-pager button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.lg-pager .lg-muted {
  margin-left: 6px;
}

.lg-muted {
  color: var(--text-3);
  font-size: 12.5px;
}

.lg-bad {
  color: var(--camp-wolf);
}

.lg-label {
  color: var(--text-3);
  font-size: 12.5px;
  font-weight: 700;
}

.lg-menu {
  position: relative;
}

.lg-menu__btn {
  width: 34px;
  height: 34px;
  font-size: 18px;
}

.lg-menu__pop {
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

.lg-menu__pop button {
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

.lg-menu__pop button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.lg-mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  justify-content: flex-end;
  background: rgba(8, 6, 12, 0.35);
}

.lg-drawer {
  width: min(560px, 100vw);
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 22px;
  background: var(--panel-raised);
  border-left: 2px solid var(--ink);
  box-shadow: -10px 0 24px rgba(0, 0, 0, 0.45);
}

.lg-drawer__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.lg-drawer__head strong {
  font: 900 17px var(--serif);
  margin-right: auto;
}

.lg-x {
  border: 0;
  background: transparent;
  color: var(--text-2);
  font-size: 16px;
  cursor: pointer;
}

kbd {
  font: 11px ui-monospace, monospace;
  border: 1px solid var(--line);
  border-bottom-width: 2px;
  border-radius: 5px;
  padding: 0 5px;
  color: var(--text-2);
}

.lg-thread {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lg-turn {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lg-turn.is-dim {
  opacity: 0.5;
}

.lg-bubble {
  max-width: 88%;
  padding: 10px 14px;
  border-radius: 16px;
  line-height: 1.75;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.lg-bubble--me {
  align-self: flex-end;
  background: var(--gold);
  color: var(--ink);
  border: 2px solid var(--ink);
  box-shadow: 2px 2px 0 var(--ink);
  border-bottom-right-radius: 5px;
  font-weight: 600;
}

.lg-bubble--ai {
  align-self: flex-start;
  background: var(--panel);
  color: var(--text-soft);
  border-bottom-left-radius: 5px;
}

.lg-md :deep(p) {
  margin: 0 0 8px;
}

.lg-md :deep(p:last-child) {
  margin-bottom: 0;
}

.lg-md :deep(ul),
.lg-md :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.lg-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  color: var(--text-3);
  font-size: 12.5px;
}

.lg-hits {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.lg-drawer__foot {
  display: flex;
  gap: 10px;
}

@media (max-width: 900px) {
  .lg-tr {
    grid-template-columns: 90px minmax(0, 1fr) 80px;
  }

  .lg-tr > :nth-child(3),
  .lg-tr > :nth-child(4) {
    display: none;
  }

  .lg-search {
    width: 100%;
    margin-left: 0;
  }
}
</style>
