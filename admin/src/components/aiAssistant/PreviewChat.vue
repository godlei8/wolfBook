<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiConfig, AdminPlaygroundResult, Board, Role } from '../../types'
import { renderMarkdown } from '../../utils/markdown'
import { answerTypeLabel, failureLabel, hitTypeLabel, intentLabel } from './formatters'

// 编排页右边的「调试与预览」：用左边还没发布的草稿答题，一段连续对话，
// 每次把这段里之前的问答当历史一起发过去，和玩家在小程序里追问时一样
const props = defineProps<{
  draft: AdminAiConfig
  modelId: number | null
  /** 从问答日志「拿到编排里调试」带来的问题：填进输入框，不自动发 */
  incoming: string | null
}>()
const emit = defineEmits<{ 'incoming-used': [] }>()

interface Turn {
  question: string
  result: AdminPlaygroundResult | null
  error: string | null
  open: boolean
}

const page = ref<'general' | 'board' | 'role'>('general')
const boardId = ref<number | null>(null)
const roleId = ref<number | null>(null)
const boards = ref<Board[]>([])
const roles = ref<Role[]>([])
const turns = ref<Turn[]>([])
const message = ref('')
const running = ref(false)
const input = ref<HTMLTextAreaElement | null>(null)
const thread = ref<HTMLElement | null>(null)

const suggestions = computed(() => props.draft.base.quickQuestions || [])

// 换场景就是另开一段对话
watch([page, boardId, roleId], clear)

watch(
  () => props.incoming,
  async (question) => {
    if (!question) return
    clear()
    page.value = 'general'
    message.value = question
    emit('incoming-used')
    await nextTick()
    input.value?.focus()
  },
  { immediate: true },
)

function clear() {
  // 换成新数组：还在路上的那条回答认得出自己已经不属于当前对话
  turns.value = []
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

/** 之前答成功的问答，按草稿的「历史窗口」截最近几条 */
function history() {
  const items = turns.value
    .filter((turn) => turn.result && !turn.result.failureType)
    .flatMap((turn) => [
      { role: 'user' as const, content: turn.question },
      { role: 'assistant' as const, content: turn.result!.answer },
    ])
  const windowSize = Math.max(2, Number(props.draft.retrieval.historyWindow) || 12)
  return items.slice(-windowSize)
}

async function scrollToEnd() {
  await nextTick()
  thread.value?.scrollTo({ top: thread.value.scrollHeight, behavior: 'smooth' })
}

async function send(question?: string) {
  const text = (question ?? message.value).trim()
  if (!text || running.value) return
  if (page.value === 'board' && !boardId.value) {
    ElMessage.warning('先选一个板子')
    return
  }
  if (page.value === 'role' && !roleId.value) {
    ElMessage.warning('先选一个角色')
    return
  }
  const turn: Turn = { question: text, result: null, error: null, open: false }
  const priorHistory = history()
  turns.value.push(turn)
  const sent = turns.value
  const index = sent.length - 1
  message.value = ''
  running.value = true
  void scrollToEnd()
  // 回答回来前清空或换了场景，这条就不再写回去
  const settle = (patch: Partial<Turn>) => {
    if (turns.value === sent && sent[index]) sent[index] = { ...sent[index], ...patch }
  }
  try {
    const result = await api.runAiPlayground({
      message: text,
      scene: page.value === 'general' ? 'general' : `${page.value}_detail`,
      pageContext: pageContext(),
      config: props.draft,
      modelId: props.modelId,
      history: priorHistory,
    })
    settle({ result })
  } catch (error) {
    settle({ error: error instanceof Error ? error.message : '试答失败' })
  } finally {
    running.value = false
    void scrollToEnd()
  }
}

function onKeydown(event: KeyboardEvent) {
  // 回车发送，Shift + 回车换行
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void send()
  }
}

function seconds(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
}

function steps(result: AdminPlaygroundResult) {
  const timings = result.timings || {}
  const list = [
    { key: 'route', label: '判断问题' },
    { key: 'retrieve', label: '查资料' },
    { key: 'generate', label: '模型生成' },
  ]
    .filter((step) => timings[step.key] != null)
    .map((step) => ({ ...step, ms: timings[step.key] }))
  const total = Math.max(1, list.reduce((sum, step) => sum + step.ms, 0))
  return list.map((step) => ({ ...step, share: step.ms / total }))
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
  <section class="pv" aria-label="调试与预览">
    <header class="pv-head">
      <div class="pv-title">
        <strong>调试与预览</strong>
        <span class="pv-note">用的是左边的草稿设置，玩家看不到</span>
      </div>
      <button v-if="turns.length" type="button" class="pv-clear" @click="clear">清空</button>
    </header>
    <div class="pv-scene">
      <button type="button" class="nt-chip pv-chip" :class="{ 'nt-chip--on': page === 'general' }" @click="page = 'general'">通用</button>
      <button type="button" class="nt-chip pv-chip" :class="{ 'nt-chip--on': page === 'board' }" @click="page = 'board'">在板子页</button>
      <button type="button" class="nt-chip pv-chip" :class="{ 'nt-chip--on': page === 'role' }" @click="page = 'role'">在角色页</button>
      <select v-if="page === 'board'" v-model="boardId" class="nt-input pv-pick" aria-label="在哪个板子详情页问">
        <option :value="null" disabled>选一个板子</option>
        <option v-for="item in boards" :key="item.id" :value="item.id">{{ item.name }}</option>
      </select>
      <select v-if="page === 'role'" v-model="roleId" class="nt-input pv-pick" aria-label="在哪个角色详情页问">
        <option :value="null" disabled>选一个角色</option>
        <option v-for="item in roles" :key="item.id" :value="item.id">{{ item.name }}</option>
      </select>
    </div>

    <div ref="thread" class="pv-thread" aria-live="polite">
      <div class="pv-bubble pv-bubble--welcome">{{ draft.base.welcomeMessage || '（还没写开场白）' }}</div>
      <div v-if="!turns.length && suggestions.length" class="pv-suggest">
        <button v-for="item in suggestions" :key="item" type="button" class="pv-suggest__item" @click="send(item)">{{ item }}</button>
      </div>

      <template v-for="(turn, index) in turns" :key="index">
        <div class="pv-bubble pv-bubble--me">{{ turn.question }}</div>
        <div v-if="!turn.result && !turn.error" class="pv-bubble pv-bubble--ai pv-wait">正在回答…</div>
        <div v-else-if="turn.error" class="pv-bubble pv-bubble--ai pv-bad">{{ turn.error }}</div>
        <div v-else-if="turn.result" class="pv-answer">
          <div class="pv-bubble pv-bubble--ai pv-md" v-html="renderMarkdown(turn.result.answer)"></div>
          <div v-if="turn.result.boards.length" class="pv-boards">
            <span v-for="board in turn.result.boards" :key="board.id" class="ai-tag ai-tag--moon">{{ board.name }}</span>
          </div>
          <div class="pv-meta">
            <span>{{ answerTypeLabel(turn.result.answerType) }}</span>
            <span>{{ seconds(turn.result.totalMs) }}</span>
            <span v-if="turn.result.failureType" class="pv-bad">{{ failureLabel(turn.result.failureType) }}</span>
            <span>资料 {{ turn.result.hits.length }} 份</span>
            <button type="button" class="pv-link" :aria-expanded="turn.open" @click="turn.open = !turn.open">{{ turn.open ? '收起过程' : '查看过程' }}</button>
          </div>
          <div v-if="turn.open" class="pv-trace">
            <div>
              <span class="pv-label">判断</span>{{ intentLabel(turn.result.intent) }}
              <span class="pv-muted">{{ turn.result.reason || '' }}</span>
            </div>
            <div>
              <span class="pv-label">交给模型的资料</span>
              <span v-if="!turn.result.hits.length" class="pv-muted">没有命中资料，按通用规则回答</span>
              <div v-for="hit in turn.result.hits" :key="hit.title + hit.sourceType" class="pv-hit">
                <span class="pv-hit__type">{{ hitTypeLabel(hit.sourceType) }}</span>
                <span class="pv-hit__title">{{ hit.title }}</span>
                <span class="pv-muted">{{ Number.isInteger(hit.score) ? hit.score : hit.score.toFixed(2) }}</span>
              </div>
            </div>
            <div>
              <span class="pv-label">耗时</span>
              <div class="pv-bar">
                <span v-for="step in steps(turn.result)" :key="step.key" :class="`pv-seg pv-seg--${step.key}`" :style="{ flexGrow: step.share }"></span>
              </div>
              <div class="pv-steps">
                <span v-for="step in steps(turn.result)" :key="step.key"><i :class="`pv-dot pv-seg--${step.key}`"></i>{{ step.label }} {{ seconds(step.ms) }}</span>
              </div>
            </div>
            <div class="pv-muted">{{ turn.result.model || '未配置模型' }}{{ turn.result.usedWebSearch ? ' · 用了联网搜索' : '' }}</div>
          </div>
        </div>
      </template>
    </div>

    <div class="pv-composer">
      <textarea
        ref="input"
        v-model="message"
        class="pv-input"
        rows="1"
        maxlength="500"
        placeholder="用左边还没发布的设置问一句…"
        @keydown="onKeydown"
      ></textarea>
      <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="running || !message.trim()" @click="send()">
        {{ running ? '回答中…' : '发送' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.pv {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: #1a1623;
}

.pv-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 16px 20px 10px;
}

.pv-title {
  display: flex;
  flex-direction: column;
}

.pv-note {
  color: var(--text-3);
  font-size: 12px;
}

.pv-clear,
.pv-link {
  border: 0;
  background: transparent;
  color: var(--moon);
  font: 600 12.5px var(--sans);
  cursor: pointer;
  padding: 0;
}

.pv-scene {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 0 20px 12px;
  border-bottom: 1px solid var(--line);
}

.pv-chip {
  height: 28px;
  padding: 0 12px;
  font-size: 12.5px;
}

.pv-pick {
  height: 32px;
  width: auto;
  min-width: 150px;
  font-size: 13px;
}

.pv-thread {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pv-bubble {
  max-width: 88%;
  padding: 10px 14px;
  border-radius: 16px;
  line-height: 1.75;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.pv-bubble--welcome {
  align-self: flex-start;
  border: 1px dashed var(--line);
  color: var(--text-2);
}

.pv-bubble--me {
  align-self: flex-end;
  background: var(--gold);
  color: var(--ink);
  border: 2px solid var(--ink);
  box-shadow: 2px 2px 0 var(--ink);
  border-bottom-right-radius: 5px;
  font-weight: 600;
}

.pv-bubble--ai {
  align-self: flex-start;
  background: var(--panel-raised);
  color: var(--text-soft);
  border-bottom-left-radius: 5px;
}

.pv-answer {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.pv-md :deep(p) {
  margin: 0 0 8px;
}

.pv-md :deep(p:last-child) {
  margin-bottom: 0;
}

.pv-md :deep(ul),
.pv-md :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.pv-md :deep(strong) {
  color: var(--text);
}

.pv-wait {
  color: var(--text-3);
}

.pv-bad {
  color: var(--camp-wolf);
}

.pv-boards {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pv-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  color: var(--text-3);
  font-size: 12px;
  padding-left: 4px;
}

.pv-suggest {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pv-suggest__item {
  border: 1px solid var(--line);
  background: transparent;
  color: var(--text-2);
  border-radius: 999px;
  padding: 4px 12px;
  font: 13px var(--sans);
  cursor: pointer;
}

.pv-suggest__item:hover {
  color: var(--text);
  border-color: var(--moon);
}

.pv-trace {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--bg);
  font-size: 13px;
}

.pv-label {
  color: var(--text-3);
  font-weight: 700;
  margin-right: 8px;
}

.pv-muted {
  color: var(--text-3);
  margin-left: 6px;
}

.pv-hit {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 0;
}

.pv-hit__type {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  background: var(--panel);
  color: var(--text-3);
  font-size: 11.5px;
}

.pv-hit__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pv-bar {
  display: flex;
  height: 8px;
  margin: 6px 0;
  border-radius: 4px;
  overflow: hidden;
  background: var(--panel);
}

.pv-seg {
  display: block;
  min-width: 2px;
}

.pv-seg--route {
  background: var(--moon);
}

.pv-seg--retrieve {
  background: var(--camp-god);
}

.pv-seg--generate {
  background: var(--camp-third);
}

.pv-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  color: var(--text-2);
  font-size: 12px;
}

.pv-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 5px;
}

.pv-composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin: 12px 16px 16px;
  padding: 8px 8px 8px 14px;
  border-radius: 14px;
  border: 1px solid var(--line);
  background: var(--bg);
}

.pv-composer:focus-within {
  border-color: var(--moon);
}

.pv-input {
  flex: 1;
  min-width: 0;
  max-height: 140px;
  border: 0;
  outline: 0;
  resize: none;
  background: transparent;
  color: var(--text);
  font: 14px/1.6 var(--sans);
  padding: 4px 0;
  field-sizing: content;
}

.pv-input::placeholder {
  color: var(--text-3);
}
</style>
