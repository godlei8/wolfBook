<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiDocument, AdminAiLog, AdminPlaygroundResult, Board, Role } from '../../types'
import { renderMarkdown } from '../../utils/markdown'
import { clampPage, pageNumbers, paginate } from '../../utils/pagination'
import { SLOW_MS, filterLogs, formatFullTime, formatLogTime, hitKind, logFilterCounts, type LogFilter } from '../../utils/aiAdmin'
import { answerTypeLabel, failureLabel, hitTypeLabel, intentLabel } from './formatters'

// 问答块：左边上面试答、下面问答记录，右边看选中的那一题（或刚试答的结果）。原来的「调试台」和「问答日志」
const props = defineProps<{
  logs: AdminAiLog[]
  documents: AdminAiDocument[]
  now: Date
  filterRequest: { filter: LogFilter; at: number } | null
}>()
const emit = defineEmits<{ reload: ['logs']; 'filter-consumed': [] }>()

const SAMPLES = [
  '女巫第一晚能自救吗？',
  '9个人新手玩什么好？',
  '预女猎白这个板子女巫能自救吗？',
  '守卫的真名是什么，他喜欢谁？',
  '今晚刀谁比较好？',
]
const FILTERS: { key: LogFilter; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'failed', label: '失败' },
  { key: 'slow', label: '慢' },
  { key: 'refused', label: '婉拒' },
]
// 一页 10 条，记录最多 500 条，翻页不用一直往下滚
const PAGE_SIZE = 10

// ---------- 试答 ----------
const message = ref('')
const page = ref<'general' | 'board' | 'role'>('general')
const boardId = ref<number | null>(null)
const roleId = ref<number | null>(null)
const boards = ref<Board[]>([])
const roles = ref<Role[]>([])
const running = ref(false)
const trial = ref<{ question: string; result: AdminPlaygroundResult } | null>(null)
const askInput = ref<HTMLTextAreaElement | null>(null)
const rightPanel = ref<HTMLElement | null>(null)

// ---------- 记录 ----------
const filter = ref<LogFilter>('all')
const logPage = ref(1)
const selectedId = ref<number | null>(null)
// 右边看的是记录还是试答结果
const viewing = ref<'log' | 'trial'>('log')
const menuOpen = ref(false)

const counts = computed(() => logFilterCounts(props.logs))
const filtered = computed(() => filterLogs(props.logs, filter.value))
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))
const visible = computed(() => paginate(filtered.value, logPage.value, PAGE_SIZE))
const pages = computed(() => pageNumbers(logPage.value, pageCount.value))
const selected = computed(() => props.logs.find((log) => log.id === selectedId.value) || null)

watch(filter, () => {
  logPage.value = 1
  selectedId.value = filtered.value[0]?.id ?? null
  viewing.value = 'log'
})
// 状态条点「失败 →」：切到那个筛选、看列表里第一条；用掉就告诉父页面清掉，下次进来还是从「全部」开始
watch(
  () => props.filterRequest,
  (request) => {
    if (!request) return
    filter.value = request.filter
    logPage.value = 1
    selectedId.value = filterLogs(props.logs, request.filter)[0]?.id ?? null
    viewing.value = 'log'
    emit('filter-consumed')
  },
  { immediate: true },
)
watch(
  () => props.logs,
  (logs) => {
    logPage.value = clampPage(logPage.value, PAGE_SIZE, filtered.value.length)
    if (!logs.some((log) => log.id === selectedId.value)) selectedId.value = filtered.value[0]?.id ?? null
  },
  { immediate: true },
)

// 窄屏上下排时，详情在列表下面，点了滚过去；并排时详情已在眼前，不动
function revealRight() {
  void nextTick(() => rightPanel.value?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }))
}

function pick(log: AdminAiLog) {
  selectedId.value = log.id
  viewing.value = 'log'
  revealRight()
}

// ---------- 右栏 ----------
const answerHtml = computed(() => (selected.value?.answer ? renderMarkdown(selected.value.answer) : ''))
const trialHtml = computed(() => (trial.value ? renderMarkdown(trial.value.result.answer) : ''))
const steps = computed(() => {
  const timings = trial.value?.result.timings || {}
  return [
    { key: 'route', label: '判断问题' },
    { key: 'retrieve', label: '查资料' },
    { key: 'generate', label: '模型生成' },
  ]
    .filter((step) => timings[step.key] != null)
    .map((step) => ({ ...step, ms: timings[step.key] }))
})
const stepTotal = computed(() => Math.max(1, steps.value.reduce((sum, step) => sum + step.ms, 0)))

function hitLabel(name: string) {
  const kind = hitKind(name, props.documents)
  return kind ? `${kind} · ${name}` : name
}

function seconds(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
}

function shortUser(openid: string | null) {
  if (!openid) return '未登录'
  return openid.length > 12 ? `${openid.slice(0, 10)}…` : openid
}

function pageContext() {
  if (page.value === 'board' && boardId.value) {
    const board = boards.value.find((item) => item.id === boardId.value)
    return { page: 'boards/detail', boardId: boardId.value, boardName: board?.name }
  }
  if (page.value === 'role' && roleId.value) {
    const role = roles.value.find((item) => item.id === roleId.value)
    return { page: 'roles/detail', roleId: roleId.value, roleName: role?.name }
  }
  return {}
}

async function run(question?: string) {
  const text = (question ?? message.value).trim()
  if (!text) {
    ElMessage.warning('先写一个问题')
    return
  }
  message.value = text
  running.value = true
  try {
    const result = await api.runAiPlayground({
      message: text,
      scene: page.value === 'general' ? 'general' : `${page.value}_detail`,
      pageContext: pageContext(),
    })
    trial.value = { question: text, result }
    viewing.value = 'trial'
    revealRight()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '试答失败')
  } finally {
    running.value = false
  }
}

function onAskKeydown(event: KeyboardEvent) {
  // 回车直接问，Shift + 回车换行
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void run()
  }
}

async function editAndAsk(log: AdminAiLog) {
  message.value = log.userMessage
  page.value = 'general'
  await nextTick()
  askInput.value?.focus()
}

function rerun(log: AdminAiLog) {
  page.value = 'general'
  void run(log.userMessage)
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
    await ElMessageBox.confirm('将清空全部问答日志记录，该操作不可恢复。', '清空问答记录', {
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    const removed = await api.clearAiLogs()
    ElMessage.success(removed ? `已清空 ${removed} 条问答记录` : '当前没有可清空的问答记录')
    emit('reload', 'logs')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '清空记录失败')
  }
}

onMounted(async () => {
  try {
    const [boardList, roleList] = await Promise.all([api.getBoards(), api.getRoles()])
    boards.value = boardList
    roles.value = roleList
  } catch {
    // 选不了页面不影响通用提问
  }
})
</script>

<template>
  <div class="qa-wrap">
  <div class="qa">
    <section class="nt-panel qa-left">
      <div class="qa-ask">
        <textarea
          ref="askInput"
          v-model="message"
          class="nt-input qa-ask__input"
          rows="2"
          maxlength="500"
          placeholder="试一题：输入问题，回车提交（Shift + 回车换行）"
          :disabled="running"
          @keydown="onAskKeydown"
        ></textarea>
        <div class="qa-ask__row">
          <span class="qa-muted">在哪问</span>
          <button type="button" class="nt-chip qa-small" :class="{ 'nt-chip--on': page === 'general' }" @click="page = 'general'">通用</button>
          <button type="button" class="nt-chip qa-small" :class="{ 'nt-chip--on': page === 'board' }" @click="page = 'board'">板子详情</button>
          <button type="button" class="nt-chip qa-small" :class="{ 'nt-chip--on': page === 'role' }" @click="page = 'role'">角色详情</button>
        </div>
        <select v-if="page === 'board'" v-model="boardId" class="nt-input qa-pick" aria-label="在哪个板子详情页问">
          <option :value="null" disabled>选一个板子</option>
          <option v-for="item in boards" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <select v-if="page === 'role'" v-model="roleId" class="nt-input qa-pick" aria-label="在哪个角色详情页问">
          <option :value="null" disabled>选一个角色</option>
          <option v-for="item in roles" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <div v-if="!message.trim()" class="qa-samples">
          <button v-for="item in SAMPLES" :key="item" type="button" class="qa-sample" :disabled="running" @click="run(item)">{{ item }}</button>
        </div>
        <div class="qa-ask__row qa-ask__row--end">
          <button v-if="trial && viewing !== 'trial'" type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="viewing = 'trial'">看上一次试答</button>
          <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="running" @click="run()">{{ running ? '正在回答…' : '试一下' }}</button>
        </div>
      </div>

      <div class="qa-filter">
        <div class="qa-filter__chips">
          <button
            v-for="item in FILTERS"
            :key="item.key"
            type="button"
            class="nt-chip qa-small"
            :class="{ 'nt-chip--on': filter === item.key }"
            @click="filter = item.key"
          >
            {{ item.label }} {{ counts[item.key] }}
          </button>
        </div>
        <div class="qa-menu">
          <button type="button" class="nt-iconbtn qa-menu__btn" aria-label="更多操作" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">⋯</button>
          <div v-if="menuOpen" class="qa-menu__pop">
            <button type="button" :disabled="!logs.length" @click="clearLogs">清空问答记录</button>
          </div>
        </div>
      </div>

      <ol v-if="visible.length" class="qa-list">
        <li v-for="log in visible" :key="log.id">
          <button
            type="button"
            class="qa-row"
            :class="{ 'is-on': viewing === 'log' && log.id === selectedId }"
            @click="pick(log)"
          >
            <span class="qa-row__q">{{ log.userMessage }}</span>
            <span class="qa-row__meta">
              <span>{{ formatLogTime(log.createTime, now) }}</span>
              <span v-if="!log.success" class="qa-bad">失败 · {{ failureLabel(log.failureType) || '未知原因' }}</span>
              <span v-else>{{ answerTypeLabel(log.answerType) }}</span>
              <span v-if="log.answerType !== 'REFUSAL'" :class="{ 'qa-warn': log.latencyMs >= SLOW_MS }">{{ seconds(log.latencyMs) }}</span>
            </span>
          </button>
        </li>
      </ol>
      <div v-else class="nt-empty">{{ logs.length ? '这个筛选下没有记录' : '还没有人问过' }}</div>
      <nav v-if="filtered.length > PAGE_SIZE" class="qa-pager" aria-label="问答记录翻页">
        <button type="button" class="qa-pager__btn" :disabled="logPage === 1" aria-label="上一页" @click="logPage--">‹</button>
        <template v-for="(item, index) in pages" :key="`${item}-${index}`">
          <span v-if="item === '…'" class="qa-pager__gap">…</span>
          <button
            v-else
            type="button"
            class="qa-pager__btn"
            :class="{ 'is-on': item === logPage }"
            :aria-current="item === logPage ? 'page' : undefined"
            @click="logPage = item"
          >
            {{ item }}
          </button>
        </template>
        <button type="button" class="qa-pager__btn" :disabled="logPage === pageCount" aria-label="下一页" @click="logPage++">›</button>
        <span class="qa-pager__info">{{ (logPage - 1) * PAGE_SIZE + 1 }}–{{ Math.min(logPage * PAGE_SIZE, filtered.length) }} / {{ filtered.length }}</span>
      </nav>
    </section>

    <section ref="rightPanel" class="nt-panel qa-right" aria-live="polite">
      <template v-if="viewing === 'trial' && trial">
        <div class="qa-head">
          <h3>{{ trial.question }}</h3>
          <span class="ai-tag ai-tag--moon">试答 · 不进记录</span>
        </div>
        <div class="qa-meta">
          <span>{{ answerTypeLabel(trial.result.answerType) }}</span>
          <span>{{ seconds(trial.result.totalMs) }}</span>
          <span>{{ trial.result.model || '未配置模型' }}{{ trial.result.usedWebSearch ? ' · 用了联网搜索' : '' }}</span>
        </div>
        <p v-if="trial.result.failureType" class="qa-bad">{{ failureLabel(trial.result.failureType) }}</p>
        <div class="qa-answer qa-markdown" v-html="trialHtml"></div>
        <div v-if="trial.result.boards.length" class="qa-boards">
          <span v-for="board in trial.result.boards" :key="board.id" class="qa-board">{{ board.name }} · {{ board.reason }}</span>
        </div>
        <div class="qa-trace">
          <div>
            <div class="qa-label">判断</div>
            <div>{{ intentLabel(trial.result.intent) }}</div>
            <div class="qa-muted">{{ trial.result.reason || '—' }}</div>
          </div>
          <div>
            <div class="qa-label">交给模型的资料（{{ trial.result.hits.length }}）</div>
            <div v-if="!trial.result.hits.length" class="qa-muted">没有命中资料，按通用规则回答</div>
            <div v-for="hit in trial.result.hits" :key="hit.title + hit.sourceType" class="qa-hit">
              <span class="qa-hit__type">{{ hitTypeLabel(hit.sourceType) }}</span>
              <span class="qa-hit__title">{{ hit.title }}</span>
              <span class="qa-muted">{{ Number.isInteger(hit.score) ? hit.score : hit.score.toFixed(2) }}</span>
            </div>
          </div>
          <div>
            <div class="qa-label">耗时 {{ seconds(trial.result.totalMs) }}</div>
            <div class="qa-bar">
              <span v-for="step in steps" :key="step.key" :class="`qa-bar__seg qa-bar__seg--${step.key}`" :style="{ flexGrow: step.ms / stepTotal }"></span>
            </div>
            <div v-for="step in steps" :key="step.key" class="qa-step">
              <span :class="`qa-dot qa-bar__seg--${step.key}`"></span>{{ step.label }}<span class="qa-muted">{{ seconds(step.ms) }}</span>
            </div>
          </div>
        </div>
      </template>

      <template v-else-if="selected">
        <div class="qa-head">
          <h3>{{ selected.userMessage }}</h3>
          <span class="ai-tag">记录</span>
        </div>
        <div class="qa-meta">
          <span>{{ formatFullTime(selected.createTime) }}</span>
          <span>{{ answerTypeLabel(selected.answerType) }}</span>
          <span>{{ seconds(selected.latencyMs) }} · {{ selected.usedWebSearch ? '联网了' : '没联网' }}</span>
          <span>用户 {{ shortUser(selected.openid) }}</span>
        </div>
        <p v-if="!selected.success" class="qa-bad">失败：{{ failureLabel(selected.failureType) || '未知原因' }}</p>
        <div v-if="selected.answer" class="qa-answer qa-markdown" v-html="answerHtml"></div>
        <p v-else class="qa-answer qa-muted">会话已被清空，找不到这次的回答</p>
        <div class="qa-label">交给模型的资料</div>
        <div class="qa-hits">
          <span v-if="!selected.hitSources.length" class="qa-muted">没有命中资料</span>
          <span
            v-for="name in selected.hitSources"
            :key="name"
            class="ai-tag"
            :class="{ 'ai-tag--moon': hitKind(name, documents) && hitKind(name, documents) !== '文档' }"
          >
            {{ hitLabel(name) }}
          </span>
        </div>
        <div class="qa-actions">
          <button type="button" class="nt-btn nt-btn--gold nt-btn--small" @click="editAndAsk(selected)">改一下再问</button>
          <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="running" @click="rerun(selected)">
            {{ running ? '正在回答…' : '用现在的设置重答' }}
          </button>
          <button type="button" class="qa-trace-id" title="点一下复制" @click="copyTrace(selected.traceId)">traceId {{ selected.traceId.slice(0, 8) }}…</button>
        </div>
      </template>

      <div v-else class="nt-empty">左边选一题，或者试答一题</div>
    </section>
  </div>
  </div>
</template>

<style scoped>
/* 按这一块自己的宽度排版：后台侧栏展开、窗口窄时也不挤 */
.qa-wrap {
  container-type: inline-size;
}

.qa {
  display: grid;
  grid-template-columns: minmax(300px, 380px) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.qa-left {
  gap: 14px;
  padding: 18px;
}

.qa-right {
  position: sticky;
  top: 24px;
  gap: 14px;
}

.qa-ask {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.qa-ask__input {
  min-height: 64px;
}

.qa-ask__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.qa-ask__row--end {
  justify-content: flex-end;
}

.qa-small {
  height: 28px;
  padding: 0 11px;
  font-size: 12.5px;
}

.qa-pick {
  height: 38px;
  font-size: 14px;
}

.qa-samples {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.qa-sample {
  border: 1px dashed var(--line);
  background: transparent;
  color: var(--text-2);
  border-radius: 10px;
  padding: 3px 9px;
  font: 12.5px var(--sans);
  cursor: pointer;
}

.qa-sample:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--text-3);
}

.qa-filter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.qa-filter__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.qa-menu {
  position: relative;
}

.qa-menu__btn {
  width: 32px;
  height: 32px;
  font-size: 18px;
}

.qa-menu__pop {
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

.qa-menu__pop button {
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

.qa-menu__pop button:hover:not(:disabled) {
  background: var(--danger-bg);
}

.qa-menu__pop button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.qa-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.qa-row {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 9px 11px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--text);
  font: 14px var(--sans);
  text-align: left;
  cursor: pointer;
}

.qa-row:hover {
  background: var(--panel-raised);
}

.qa-row.is-on {
  border-color: var(--gold);
  background: var(--warn-bg);
}

.qa-row__q {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qa-row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  color: var(--text-3);
  font-size: 12px;
}

.qa-pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.qa-pager__btn {
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--text-2);
  font: 600 13px var(--sans);
  cursor: pointer;
}

.qa-pager__btn:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--text-3);
}

.qa-pager__btn.is-on {
  background: var(--moon);
  border-color: var(--moon);
  color: var(--ink);
}

.qa-pager__btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.qa-pager__gap {
  color: var(--text-3);
  padding: 0 2px;
}

.qa-pager__info {
  flex-basis: 100%;
  text-align: center;
  color: var(--text-3);
  font-size: 12px;
}

.qa-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.qa-head h3 {
  margin: 0;
  font: 900 18px/1.4 var(--serif);
}

.qa-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  color: var(--text-2);
  font-size: 13px;
}

.qa-answer {
  margin: 0;
  padding: 14px 16px;
  border-radius: 14px;
  background: var(--panel-raised);
  color: var(--text-soft);
  font-size: 14px;
  line-height: 1.75;
}

.qa-markdown :deep(p) {
  margin: 0 0 8px;
}

.qa-markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.qa-markdown :deep(ul),
.qa-markdown :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.qa-markdown :deep(strong) {
  color: var(--text);
}

.qa-markdown :deep(h1),
.qa-markdown :deep(h2),
.qa-markdown :deep(h3),
.qa-markdown :deep(h4) {
  margin: 8px 0 6px;
  font: 900 15px var(--serif);
  color: var(--text);
}

.qa-boards {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.qa-board {
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid var(--line);
  color: var(--text-2);
  font-size: 13px;
}

.qa-label {
  color: var(--text-3);
  font-size: 12.5px;
  font-weight: 700;
  margin-bottom: 4px;
}

.qa-hits {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.qa-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding-top: 4px;
}

.qa-trace-id {
  border: 0;
  background: transparent;
  color: var(--text-3);
  font: 12px ui-monospace, monospace;
  cursor: copy;
}

.qa-trace {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
  font-size: 13px;
}

.qa-hit {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0;
}

.qa-hit__type {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  background: var(--bg);
  color: var(--text-3);
  font-size: 11.5px;
}

.qa-hit__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qa-bar {
  display: flex;
  height: 8px;
  margin: 6px 0 8px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--bg);
}

.qa-bar__seg {
  display: block;
  min-width: 2px;
}

.qa-bar__seg--route {
  background: var(--moon);
}

.qa-bar__seg--retrieve {
  background: var(--camp-god);
}

.qa-bar__seg--generate {
  background: var(--camp-third);
}

.qa-step {
  display: flex;
  align-items: center;
  gap: 8px;
}

.qa-step .qa-muted {
  margin-left: auto;
}

.qa-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.qa-muted {
  color: var(--text-3);
  font-size: 12.5px;
}

.qa-bad {
  margin: 0;
  color: var(--camp-wolf);
}

.qa-warn {
  color: var(--camp-god);
}

@container (max-width: 980px) {
  .qa-trace {
    grid-template-columns: 1fr;
  }
}

@container (max-width: 760px) {
  .qa {
    grid-template-columns: 1fr;
  }

  .qa-right {
    position: static;
  }
}
</style>
