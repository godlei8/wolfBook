<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AdminAiLog } from '../../types'
import { go } from '../../utils/route'
import {
  SLOW_MS,
  aiPath,
  avgLatency,
  dailyCounts,
  filterLogs,
  logsInRange,
  slowest,
  topQuestions,
  typeShare,
  type DataRange,
} from '../../utils/aiAdmin'
import { logsRequest } from './composeDraft'

// 数据：都用已拉到的最近 500 条问答在页面上算，不加后端
const props = defineProps<{
  logs: AdminAiLog[]
  now: Date
}>()

const RANGES: { key: DataRange; label: string }[] = [
  { key: '7', label: '近 7 天' },
  { key: '30', label: '近 30 天' },
  { key: 'all', label: '全部' },
]
const TYPE_COLORS: Record<string, string> = {
  RAG_ANSWER: 'var(--camp-villager)',
  STRUCTURED_RECOMMENDATION: 'var(--camp-god)',
  WEB_AUGMENTED_ANSWER: 'var(--moon)',
  REFUSAL: 'var(--camp-third)',
  FAILED: 'var(--camp-wolf)',
}

const range = ref<DataRange>('7')
const scoped = computed(() => logsInRange(props.logs, range.value, props.now))
const today = computed(() => filterLogs(props.logs, 'today', props.now).length)
const failed = computed(() => scoped.value.filter((log) => !log.success).length)
const refused = computed(() => filterLogs(scoped.value, 'refused', props.now).length)
const slowCount = computed(() => slowest(scoped.value, scoped.value.length).filter((log) => log.latencyMs >= SLOW_MS).length)
const todayKey = computed(() => {
  const now = props.now
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
})
const average = computed(() => avgLatency(scoped.value))
const days = computed(() => dailyCounts(scoped.value, range.value, props.now))
const peak = computed(() => Math.max(1, ...days.value.map((day) => day.count)))
const share = computed(() => typeShare(scoped.value).filter((item) => item.count > 0))
const top = computed(() => topQuestions(scoped.value, 5))
const slow = computed(() => slowest(scoped.value, 5))

function percent(ratio: number) {
  return `${Math.round(ratio * 100)}%`
}

function seconds(ms: number) {
  return `${(ms / 1000).toFixed(1)} 秒`
}

function searchFor(text: string) {
  logsRequest.value = { search: text }
  go(aiPath('logs'))
}

function openLog(id: number) {
  logsRequest.value = { logId: id }
  go(aiPath('logs'))
}
</script>

<template>
  <div class="dt">
    <header class="dt-top">
      <div class="dt-title">
        <h1>数据</h1>
        <span class="dt-muted">按最近 500 条问答算</span>
      </div>
      <div class="dt-ranges">
        <button
          v-for="item in RANGES"
          :key="item.key"
          type="button"
          class="nt-chip dt-chip"
          :class="{ 'nt-chip--on': range === item.key }"
          @click="range = item.key"
        >
          {{ item.label }}
        </button>
      </div>
    </header>

    <div class="dt-body">
      <div class="dt-kpis">
        <div class="dt-kpi">
          <span class="dt-muted">提问</span>
          <strong>{{ scoped.length }}</strong>
          <span class="dt-muted">今天 {{ today }} 问</span>
        </div>
        <div class="dt-kpi">
          <span class="dt-muted">失败</span>
          <strong :class="failed ? 'is-bad' : 'is-ok'">{{ failed }}</strong>
          <span class="dt-muted">占比 {{ scoped.length ? percent(failed / scoped.length) : '0%' }}</span>
        </div>
        <div class="dt-kpi">
          <span class="dt-muted">平均耗时</span>
          <strong>{{ average == null ? '—' : (average / 1000).toFixed(1) }}<small v-if="average != null"> 秒</small></strong>
          <span class="dt-muted">超过 10 秒的 {{ slowCount }} 条</span>
        </div>
        <div class="dt-kpi">
          <span class="dt-muted">婉拒</span>
          <strong>{{ refused }}</strong>
          <span class="dt-muted">问了拦截词，直接婉拒</span>
        </div>
      </div>

      <div class="dt-row">
        <section class="dt-card dt-grow">
          <div class="dt-card__head">
            <h2>每天提问</h2>
            <span class="dt-muted">{{ range === 'all' ? '有提问的日子，最多 30 天' : '每天一根' }}</span>
          </div>
          <div v-if="days.length" class="dt-bars" :class="{ 'is-dense': days.length > 14 }">
            <div v-for="day in days" :key="day.key" class="dt-bar" :title="`${day.label}：${day.count} 问`">
              <span class="dt-bar__count">{{ day.count || '' }}</span>
              <i :class="{ 'is-peak': day.count === peak && day.count > 0, 'is-today': day.key === todayKey }" :style="{ height: `${Math.max(day.count ? 6 : 2, (day.count / peak) * 100)}%` }"></i>
              <span class="dt-bar__label">{{ day.label }}</span>
            </div>
          </div>
          <div v-else class="nt-empty">这段时间没有提问</div>
        </section>
        <section class="dt-card dt-side">
          <h2>回答类型</h2>
          <template v-if="share.length">
            <div class="dt-split">
              <i v-for="item in share" :key="item.key" :style="{ width: percent(item.ratio), background: TYPE_COLORS[item.key] }"></i>
            </div>
            <div v-for="item in share" :key="item.key" class="dt-legend">
              <span><i class="dt-dot" :style="{ background: TYPE_COLORS[item.key] }"></i>{{ item.label }}</span>
              <span class="dt-muted">{{ item.count }} · {{ percent(item.ratio) }}</span>
            </div>
          </template>
          <div v-else class="dt-muted">没有数据</div>
        </section>
      </div>

      <div class="dt-row">
        <section class="dt-card dt-grow">
          <h2>问得最多</h2>
          <button v-for="item in top" :key="item.text" type="button" class="dt-line" @click="searchFor(item.text)">
            <span class="dt-line__text">{{ item.text }}</span>
            <span class="dt-muted">{{ item.count }} 次</span>
          </button>
          <div v-if="!top.length" class="dt-muted">没有数据</div>
        </section>
        <section class="dt-card dt-grow">
          <h2>最慢的几题</h2>
          <button v-for="item in slow" :key="item.id" type="button" class="dt-line" @click="openLog(item.id)">
            <span class="dt-line__text">{{ item.userMessage }}</span>
            <span :class="item.latencyMs >= SLOW_MS ? 'is-warn' : 'dt-muted'">{{ seconds(item.latencyMs) }}</span>
          </button>
          <div v-if="!slow.length" class="dt-muted">没有数据</div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dt {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.dt-top {
  min-height: 64px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--line);
}

.dt-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.dt-title h1 {
  margin: 0;
  font: 900 22px var(--serif);
}

.dt-ranges {
  display: flex;
  gap: 6px;
}

.dt-chip {
  height: 30px;
  font-size: 13px;
}

.dt-body {
  padding: 18px 24px 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dt-muted {
  color: var(--text-3);
  font-size: 12.5px;
}

.dt-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.dt-kpi {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 14px 18px;
  border-radius: 16px;
  background: var(--panel);
}

.dt-kpi strong {
  font: 900 30px var(--serif);
}

.dt-kpi strong small {
  font: 400 13px var(--sans);
  color: var(--text-3);
}

.is-ok {
  color: var(--camp-villager);
}

.is-bad {
  color: var(--camp-wolf);
}

.is-warn {
  color: var(--camp-god);
  font-size: 12.5px;
}

.dt-row {
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.dt-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 18px;
  border-radius: 16px;
  background: var(--panel);
  min-width: 0;
}

.dt-card h2 {
  margin: 0;
  font: 900 16px var(--serif);
}

.dt-card__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.dt-grow {
  flex: 1;
}

.dt-side {
  width: 340px;
  flex: none;
}

.dt-bars {
  display: flex;
  align-items: stretch;
  gap: 10px;
  height: 180px;
}

.dt-bars.is-dense {
  gap: 3px;
}

.dt-bar {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  gap: 4px;
}

.dt-bar i {
  display: block;
  width: 100%;
  max-width: 48px;
  border-radius: 7px 7px 3px 3px;
  background: var(--panel-raised);
}

.dt-bar i.is-peak {
  background: var(--moon);
}

.dt-bar i.is-today {
  outline: 2px solid var(--gold);
  outline-offset: 1px;
}

.dt-bar__count {
  color: var(--text-2);
  font-size: 12px;
  min-height: 16px;
}

.dt-bar__label {
  color: var(--text-3);
  font-size: 11.5px;
  white-space: nowrap;
}

.dt-bars.is-dense .dt-bar__label,
.dt-bars.is-dense .dt-bar__count {
  font-size: 10px;
}

.dt-bars.is-dense .dt-bar:nth-child(odd) .dt-bar__label {
  visibility: hidden;
}

.dt-split {
  display: flex;
  height: 14px;
  border-radius: 7px;
  overflow: hidden;
}

.dt-split i {
  display: block;
}

.dt-legend {
  display: flex;
  justify-content: space-between;
  font-size: 13.5px;
}

.dt-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-right: 8px;
}

.dt-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 8px;
  margin: 0 -8px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--text);
  font: 14px var(--sans);
  text-align: left;
  cursor: pointer;
}

.dt-line:hover {
  background: var(--panel-raised);
}

.dt-line__text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1000px) {
  .dt-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dt-row {
    flex-direction: column;
  }

  .dt-side {
    width: auto;
  }
}
</style>
