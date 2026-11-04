<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AdminAiConfig, AdminConfigVersion } from '../../types'
import { configChanges, formatFullTime } from '../../utils/aiAdmin'

// 编排页的「历史版本」：左边时间线，右边是选中版本和线上版本的对比，「用它覆盖草稿」再发布就退回去了
const props = defineProps<{
  versions: AdminConfigVersion[]
  live: AdminAiConfig
  liveModelName: string | null
  draftChanges: number
  liveVersionId: number | null
  liveModelId: number | null
}>()
const emit = defineEmits<{ close: []; apply: [version: AdminConfigVersion] }>()

const selectedId = ref<number | null>(null)
const expanded = ref<string | null>(null)

watch(
  () => props.versions,
  (list) => {
    if (!list.some((item) => item.id === selectedId.value)) {
      // 默认看上一个版本（和线上比才有意义）；只有一个版本时就看它自己
      selectedId.value = (list[1] || list[0])?.id ?? null
    }
  },
  { immediate: true },
)

const selected = computed(() => props.versions.find((item) => item.id === selectedId.value) || null)
const isLive = computed(() => selected.value != null && selected.value.id === props.liveVersionId)
// 快照读不出来（坏数据）时 config 是 null：不对比，也不能拿来覆盖草稿
const readable = computed(() => selected.value?.config != null)
const changes = computed(() =>
  selected.value && readable.value
    ? configChanges(props.live, selected.value.config, props.liveModelName, selected.value.modelName, props.liveModelId, selected.value.modelId)
    : [],
)
</script>

<template>
  <div class="vd-mask" @click.self="emit('close')">
    <aside class="vd" role="dialog" aria-label="历史版本">
      <header class="vd-head">
        <strong>历史版本</strong>
        <button type="button" class="vd-x" aria-label="关闭" @click="emit('close')">✕</button>
      </header>
      <div v-if="!versions.length" class="nt-empty">还没发布过版本。在编排页改好设置点「发布」，就会记下第一个版本。</div>
      <div v-else class="vd-body">
        <ol class="vd-line">
          <li v-if="draftChanges" class="vd-item vd-item--draft">
            <strong>草稿</strong> <span class="ai-tag ai-tag--warn">{{ draftChanges }} 处</span>
            <div class="vd-muted">存在这台电脑的浏览器里</div>
          </li>
          <li
            v-for="item in versions"
            :key="item.id"
            class="vd-item"
            :class="{ 'vd-item--live': item.id === liveVersionId, 'is-on': item.id === selectedId }"
          >
            <button type="button" class="vd-pick" @click="selectedId = item.id">
              <strong>v{{ item.id }}</strong>
              <span v-if="item.id === liveVersionId" class="ai-tag ai-tag--moon">线上</span>
              <span class="vd-muted">{{ formatFullTime(item.createTime) }} · {{ item.publishedBy || '—' }}</span>
              <span v-if="item.notes" class="vd-notes">{{ item.notes }}</span>
            </button>
          </li>
        </ol>
        <section v-if="selected" class="vd-diff">
          <strong v-if="isLive">v{{ selected.id }} 就是现在线上的版本</strong>
          <strong v-else>v{{ selected.id }} 和现在线上的比</strong>
          <div class="vd-muted">模型 {{ selected.modelName || '默认配置' }}</div>
          <p v-if="!readable" class="vd-muted">这个版本的快照读不出来，没法对比或恢复</p>
          <p v-else-if="!isLive && !changes.length" class="vd-muted">和线上一模一样</p>
          <ul v-else-if="!isLive" class="vd-changes">
            <li v-for="change in changes" :key="change.field">
              <span class="vd-label">{{ change.label }}</span>
              <template v-if="change.long">
                <button type="button" class="vd-link" @click="expanded = expanded === change.field ? null : change.field">
                  {{ expanded === change.field ? '收起' : '改了，展开看前后' }}
                </button>
                <div v-if="expanded === change.field" class="vd-long">
                  <div><span class="vd-muted">线上</span><p>{{ change.before }}</p></div>
                  <div><span class="vd-muted">v{{ selected.id }}</span><p>{{ change.after }}</p></div>
                </div>
              </template>
              <span v-else><del>{{ change.before }}</del> → <ins>{{ change.after }}</ins></span>
            </li>
          </ul>
          <div v-if="!isLive && readable" class="vd-actions">
            <button type="button" class="nt-btn nt-btn--gold nt-btn--small" @click="emit('apply', selected)">用 v{{ selected.id }} 覆盖草稿</button>
            <span class="vd-muted">覆盖后还要点「发布」才生效</span>
          </div>
        </section>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.vd-mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  background: rgba(8, 6, 12, 0.55);
  display: flex;
  justify-content: flex-end;
}

.vd {
  width: min(640px, 100vw);
  height: 100%;
  overflow-y: auto;
  background: var(--panel-raised);
  border-left: 2px solid var(--ink);
  box-shadow: -10px 0 24px rgba(0, 0, 0, 0.45);
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.vd-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font: 900 18px var(--serif);
}

.vd-x {
  border: 0;
  background: transparent;
  color: var(--text-2);
  font-size: 16px;
  cursor: pointer;
}

.vd-body {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.vd-line {
  list-style: none;
  margin: 0;
  padding: 0 0 0 22px;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.vd-line::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: var(--line);
}

.vd-item {
  position: relative;
  font-size: 13px;
}

.vd-item::before {
  content: '';
  position: absolute;
  left: -21px;
  top: 10px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--panel);
  border: 2px solid var(--text-3);
}

.vd-item--draft {
  padding: 4px 8px;
}

.vd-item--draft::before {
  border-color: var(--camp-god);
  background: var(--warn-bg);
}

.vd-item--live::before {
  border-color: var(--moon);
  background: var(--moon);
}

.vd-pick {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 8px;
  padding: 6px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text);
  font: 13px var(--sans);
  text-align: left;
  cursor: pointer;
}

.vd-item.is-on .vd-pick {
  background: var(--panel);
  box-shadow: inset 3px 0 0 var(--gold);
}

.vd-notes {
  flex-basis: 100%;
  color: var(--text-2);
}

.vd-muted {
  color: var(--text-3);
  font-size: 12px;
}

.vd-diff {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border-radius: 14px;
  background: var(--panel);
}

.vd-changes {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13.5px;
}

.vd-changes li {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.vd-label {
  color: var(--text-2);
  font-weight: 700;
  font-size: 12.5px;
}

.vd-link {
  align-self: flex-start;
  border: 0;
  background: transparent;
  color: var(--moon);
  font: 600 12.5px var(--sans);
  padding: 0;
  cursor: pointer;
}

.vd-long {
  display: grid;
  gap: 8px;
}

.vd-long p {
  margin: 2px 0 0;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--bg);
  white-space: pre-wrap;
  line-height: 1.7;
}

del {
  color: var(--camp-wolf);
  background: var(--danger-bg);
  padding: 0 4px;
  border-radius: 4px;
}

ins {
  color: var(--camp-villager);
  background: var(--ok-bg);
  text-decoration: none;
  padding: 0 4px;
  border-radius: 4px;
}

.vd-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

@media (max-width: 640px) {
  .vd-body {
    grid-template-columns: 1fr;
  }
}
</style>
