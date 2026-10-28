<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../services/api'
import type { AdminPlaygroundResult, Board, Role } from '../../types'
import { renderMarkdown } from '../../utils/markdown'
import { answerTypeLabel, failureLabel, hitTypeLabel, intentLabel, latencyTone } from './formatters'

// 调试台：用当前生效的配置和模型答一题，看问题被判成哪一类、查到了哪些资料、每一步花了多久。
// 不建会话、不写日志，改完 Prompt 或换了模型可以在这里先试。

const SAMPLES = [
  '女巫第一晚能自救吗？',
  '9个人新手玩什么好？',
  '预女猎白这个板子女巫能自救吗？',
  '守卫的真名是什么，他喜欢谁？',
  '今晚刀谁比较好？',
]

const message = ref('')
const page = ref<'general' | 'board' | 'role'>('general')
const boardId = ref<number | null>(null)
const roleId = ref<number | null>(null)
const boards = ref<Board[]>([])
const roles = ref<Role[]>([])
const running = ref(false)
const result = ref<AdminPlaygroundResult | null>(null)
const askedQuestion = ref('')

const answerHtml = computed(() => (result.value ? renderMarkdown(result.value.answer) : ''))
const steps = computed(() => {
  const timings = result.value?.timings || {}
  return [
    { key: 'route', label: '判断问题' },
    { key: 'retrieve', label: '查资料' },
    { key: 'generate', label: '模型生成' },
  ]
    .filter((step) => timings[step.key] != null)
    .map((step) => ({ ...step, ms: timings[step.key] }))
})
const stepTotal = computed(() => Math.max(1, steps.value.reduce((sum, step) => sum + step.ms, 0)))

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
    result.value = await api.runAiPlayground({ message: text, scene: page.value === 'general' ? 'general' : `${page.value}_detail`, pageContext: pageContext() })
    askedQuestion.value = text
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '调试失败')
  } finally {
    running.value = false
  }
}

function seconds(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
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
  <div class="pg">
    <section class="pg-ask">
      <div class="pg-kicker">PLAYGROUND</div>
      <p class="pg-intro">用当前生效的配置和模型答一题，不进问答日志。改了 Prompt、换了模型、发布了知识库之后，先在这里试几题。</p>

      <div class="pg-page">
        <el-radio-group v-model="page" size="small">
          <el-radio-button value="general">通用提问</el-radio-button>
          <el-radio-button value="board">在板子详情页问</el-radio-button>
          <el-radio-button value="role">在角色详情页问</el-radio-button>
        </el-radio-group>
        <el-select v-if="page === 'board'" v-model="boardId" size="small" filterable placeholder="选一个板子" class="pg-pick">
          <el-option v-for="item in boards" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-if="page === 'role'" v-model="roleId" size="small" filterable placeholder="选一个角色" class="pg-pick">
          <el-option v-for="item in roles" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </div>

      <el-input
        v-model="message"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="像小程序用户那样提问，比如「这个板子女巫能自救吗？」"
        @keydown.ctrl.enter="run()"
      />
      <div class="pg-actions">
        <div class="pg-samples">
          <button v-for="item in SAMPLES" :key="item" type="button" class="nt-chip pg-sample" :disabled="running" @click="run(item)">{{ item }}</button>
        </div>
        <el-button type="primary" :loading="running" @click="run()">{{ running ? '正在回答…' : '试一下' }}</el-button>
      </div>
    </section>

    <section v-if="result" class="pg-result">
      <div class="pg-answer">
        <div class="pg-answer__head">
          <span class="pg-question">问：{{ askedQuestion }}</span>
          <el-tag size="small" effect="plain">{{ answerTypeLabel(result.answerType) }}</el-tag>
        </div>
        <div class="pg-markdown" v-html="answerHtml" />
        <div v-if="result.boards.length" class="pg-boards">
          <span v-for="board in result.boards" :key="board.id" class="pg-board">{{ board.name }} · {{ board.reason }}</span>
        </div>
      </div>

      <aside class="pg-trace">
        <div class="pg-trace__block">
          <div class="pg-label">判断</div>
          <div class="pg-intent">{{ intentLabel(result.intent) }}</div>
          <div class="pg-meta">{{ result.reason || '—' }}</div>
        </div>

        <div class="pg-trace__block">
          <div class="pg-label">交给模型的资料（{{ result.hits.length }}）</div>
          <div v-if="!result.hits.length" class="pg-meta">没有命中资料，按通用规则回答</div>
          <div v-for="hit in result.hits" :key="hit.title + hit.sourceType" class="pg-hit">
            <span class="pg-hit__type">{{ hitTypeLabel(hit.sourceType) }}</span>
            <span class="pg-hit__title">{{ hit.title }}</span>
            <span class="pg-hit__score">{{ Number.isInteger(hit.score) ? hit.score : hit.score.toFixed(2) }}</span>
          </div>
        </div>

        <div class="pg-trace__block">
          <div class="pg-label">耗时 <el-tag size="small" :type="latencyTone(result.totalMs)">{{ seconds(result.totalMs) }}</el-tag></div>
          <div class="pg-bar">
            <span v-for="step in steps" :key="step.key" :class="`pg-bar__seg pg-bar__seg--${step.key}`" :style="{ flexGrow: step.ms / stepTotal }" />
          </div>
          <div v-for="step in steps" :key="step.key" class="pg-step">
            <span :class="`pg-dot pg-dot--${step.key}`" />{{ step.label }}<span class="pg-step__ms">{{ seconds(step.ms) }}</span>
          </div>
        </div>

        <div class="pg-trace__block">
          <div class="pg-label">模型</div>
          <div class="pg-meta">{{ result.model || '未配置' }}{{ result.usedWebSearch ? ' · 用了联网搜索' : '' }}</div>
          <div v-if="result.failureType" class="pg-fail">{{ failureLabel(result.failureType) }}</div>
        </div>
      </aside>
    </section>
  </div>
</template>

<style scoped>
.pg {
  display: grid;
  gap: 16px;
}

.pg-ask,
.pg-answer,
.pg-trace {
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.02);
}

.pg-ask {
  display: grid;
  gap: 12px;
}

.pg-kicker,
.pg-label {
  color: var(--gold);
  font-size: 11px;
  letter-spacing: 0.18em;
}

.pg-intro,
.pg-meta {
  margin: 0;
  color: var(--text-3);
  font-size: 13px;
  line-height: 1.6;
}

.pg-page {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.pg-pick {
  width: 200px;
}

.pg-actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.pg-samples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.pg-sample {
  cursor: pointer;
}

.pg-sample:disabled {
  cursor: wait;
  opacity: 0.5;
}

.pg-result {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

.pg-answer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--panel-line);
}

.pg-question {
  color: var(--text-2);
  font-size: 14px;
}

.pg-markdown {
  color: var(--text);
  font-size: 14px;
  line-height: 1.8;
}

.pg-markdown :deep(h4) {
  margin: 14px 0 6px;
  color: var(--gold);
  font-family: var(--serif);
  font-size: 15px;
}

.pg-markdown :deep(p),
.pg-markdown :deep(ul) {
  margin: 0 0 8px;
}

.pg-markdown :deep(ul) {
  padding-left: 20px;
}

.pg-markdown :deep(strong) {
  color: var(--gold);
}

.pg-markdown :deep(blockquote) {
  margin: 8px 0;
  padding-left: 10px;
  border-left: 3px solid var(--moon);
  color: var(--text-3);
}

.pg-boards {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.pg-board {
  padding: 4px 10px;
  border: 1px solid var(--panel-line);
  border-radius: 999px;
  color: var(--text-2);
  font-size: 12px;
}

.pg-trace {
  display: grid;
  gap: 16px;
}

.pg-trace__block {
  display: grid;
  gap: 6px;
}

.pg-intent {
  color: var(--text);
  font-family: var(--serif);
  font-size: 18px;
}

.pg-hit {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.pg-hit__type {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  background: rgba(95, 227, 224, 0.12);
  color: var(--moon);
  font-size: 11px;
}

.pg-hit__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--text-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pg-hit__score,
.pg-step__ms {
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.pg-bar {
  display: flex;
  gap: 2px;
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
}

.pg-bar__seg {
  min-width: 3px;
}

.pg-bar__seg--route,
.pg-dot--route {
  background: var(--moon);
}

.pg-bar__seg--retrieve,
.pg-dot--retrieve {
  background: #8f7cf7;
}

.pg-bar__seg--generate,
.pg-dot--generate {
  background: var(--gold);
}

.pg-step {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  font-size: 13px;
}

.pg-step__ms {
  margin-left: auto;
}

.pg-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.pg-fail {
  color: #ff8a7a;
  font-size: 13px;
}

@media (max-width: 960px) {
  .pg-result {
    grid-template-columns: 1fr;
  }

  .pg-actions {
    flex-direction: column;
  }
}
</style>
