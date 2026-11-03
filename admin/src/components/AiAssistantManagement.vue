<script setup lang="ts">
import PageHeader from './ui/PageHeader.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../services/api'
import type { AdminAiConfig, AdminAiDocument, AdminAiLog, AdminAiModel, AdminAiVersion } from '../types'
import { go, route } from '../utils/route'
import {
  aiPath,
  currentVersion,
  onlineCount,
  parseAiPath,
  pendingPublish,
  todayStats,
  type AiTab,
  type LogFilter,
} from '../utils/aiAdmin'
import QaPanel from './aiAssistant/QaPanel.vue'
import KnowledgePanel from './aiAssistant/KnowledgePanel.vue'
import SettingsPanel from './aiAssistant/SettingsPanel.vue'
import { personaChanges, settingsDirtyCount } from './aiAssistant/settingsDrafts'

// AI 助手页：顶上一条状态，下面「问答 / 资料 / 设置」三块。这里只管取数、状态条和切换，各块的事在各自的组件里
export type AiReload = 'config' | 'models' | 'documents' | 'versions' | 'logs'

const loading = ref(false)
const config = ref<AdminAiConfig | null>(null)
const models = ref<AdminAiModel[]>([])
const documents = ref<AdminAiDocument[]>([])
const versions = ref<AdminAiVersion[]>([])
const logs = ref<AdminAiLog[]>([])
const now = ref(new Date())
// 状态条点「失败」时让问答块切到失败筛选；带上时间戳，同一个筛选连点两次也能触发
const filterRequest = ref<{ filter: LogFilter; at: number } | null>(null)

const location = computed(() => parseAiPath(route.value.aiParts))
const TABS: { key: AiTab; label: string }[] = [
  { key: 'qa', label: '问答' },
  { key: 'knowledge', label: '资料' },
  { key: 'settings', label: '设置' },
]

// 设为当前的模型停用了或没填 Key 时，后端会退回默认 DeepSeek 配置，这里也照实显示
const activeModel = computed(() => models.value.find((model) => model.active && model.enabled && model.apiKey) || null)
const version = computed(() => currentVersion(versions.value))
const online = computed(() => onlineCount(documents.value, version.value?.id ?? null))
const pending = computed(() => pendingPublish(documents.value, version.value?.id ?? null))
const today = computed(() => todayStats(logs.value, now.value))

const LOADERS: Record<AiReload, () => Promise<void>> = {
  config: async () => {
    config.value = await api.getAiConfig()
  },
  models: async () => {
    models.value = await api.getAiModels()
  },
  documents: async () => {
    documents.value = await api.getAiDocuments()
  },
  versions: async () => {
    versions.value = await api.getAiVersions()
  },
  logs: async () => {
    logs.value = await api.getAiLogs()
  },
}

async function reload(...what: AiReload[]) {
  const targets = what.length ? what : (Object.keys(LOADERS) as AiReload[])
  loading.value = true
  try {
    await Promise.all(targets.map((key) => LOADERS[key]()))
    now.value = new Date()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 助手数据加载失败')
  } finally {
    loading.value = false
  }
}

function showFailed() {
  filterRequest.value = { filter: 'failed', at: Date.now() }
  go(aiPath('qa'))
}

// 设置里有没保存的改动时，刷新 / 关页面先提醒一次（在后台里切来切去不丢，草稿记在内存里）
function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (settingsDirtyCount.value > 0) {
    event.preventDefault()
    event.returnValue = ''
  }
}
watch(
  settingsDirtyCount,
  (count) => {
    if (count > 0) window.addEventListener('beforeunload', warnBeforeUnload)
    else window.removeEventListener('beforeunload', warnBeforeUnload)
  },
  { immediate: true },
)

// 页面开着过了午夜，「今天」也要跟着变
const clock = window.setInterval(() => {
  now.value = new Date()
}, 60000)

onMounted(() => reload())
onBeforeUnmount(() => {
  window.clearInterval(clock)
  window.removeEventListener('beforeunload', warnBeforeUnload)
})
</script>

<template>
  <PageHeader eyebrow="ASSISTANT" title="AI 助手" description="看大家问了什么、管 AI 用的资料、调人设和模型。">
    <template #actions>
      <button class="nt-btn nt-btn--ghost" type="button" :disabled="loading" @click="reload()">{{ loading ? '同步中…' : '刷新' }}</button>
    </template>
  </PageHeader>

  <section class="ai-strip" aria-label="AI 助手现在的状态">
    <span class="ai-strip__item" :class="config && !config.base.enabled ? 'is-bad' : ''">
      <span class="nt-dot" :style="{ background: config && !config.base.enabled ? 'var(--camp-wolf)' : 'var(--camp-villager)' }"></span>
      {{ !config ? '…' : config.base.enabled ? '已开启' : '已关闭' }}
    </span>
    <span class="ai-strip__sep"></span>
    <a class="ai-strip__item ai-strip__link" :href="`#/${aiPath('settings', 'models')}`">
      <span class="ai-strip__key">模型</span>{{ activeModel ? activeModel.name : '默认 DeepSeek 配置' }}
    </a>
    <span class="ai-strip__sep"></span>
    <span class="ai-strip__item">
      <span class="ai-strip__key">资料</span>
      <a v-if="!version" class="ai-tag ai-tag--bad" :href="`#/${aiPath('knowledge')}`">还没发布过 →</a>
      <template v-else>
        线上 {{ online }} 份
        <a v-if="pending.length" class="ai-tag ai-tag--warn" :href="`#/${aiPath('knowledge')}`">{{ pending.length }} 份上传文档待发布 →</a>
      </template>
    </span>
    <span class="ai-strip__sep"></span>
    <span class="ai-strip__item">
      <span class="ai-strip__key">今天</span>{{ today.total }} 问
      <button v-if="today.failed" class="ai-tag ai-tag--bad" type="button" @click="showFailed">失败 {{ today.failed }} →</button>
      <span v-if="today.slow" class="ai-tag">慢 {{ today.slow }}</span>
    </span>
    <template v-if="settingsDirtyCount">
      <span class="ai-strip__sep"></span>
      <a class="ai-tag ai-tag--warn" :href="`#/${aiPath('settings', personaChanges ? 'persona' : 'advanced')}`">设置有 {{ settingsDirtyCount }} 处没保存 →</a>
    </template>
  </section>

  <nav class="nt-seg ai-tabs" aria-label="AI 助手分块">
    <button
      v-for="tab in TABS"
      :key="tab.key"
      type="button"
      :class="{ on: location.tab === tab.key }"
      :aria-current="location.tab === tab.key ? 'page' : undefined"
      @click="go(aiPath(tab.key))"
    >
      {{ tab.label }}
    </button>
  </nav>

  <QaPanel
    v-if="location.tab === 'qa'"
    :logs="logs"
    :documents="documents"
    :now="now"
    :filter-request="filterRequest"
    @reload="reload"
    @filter-consumed="filterRequest = null"
  />
  <KnowledgePanel
    v-else-if="location.tab === 'knowledge'"
    :documents="documents"
    :versions="versions"
    :now="now"
    @reload="reload"
  />
  <SettingsPanel
    v-else
    :config="config"
    :section="location.section"
    @saved="config = $event"
    @models-changed="reload('models')"
  />
</template>

<style scoped>
.ai-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 16px;
  padding: 12px 18px;
  border-radius: 16px;
  background: var(--panel-raised);
  font-size: 14px;
}

.ai-strip__item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text);
}

.ai-strip__item.is-bad {
  color: var(--camp-wolf);
}

.ai-strip__link {
  text-decoration: none;
}

.ai-strip__link:hover {
  color: var(--moon);
}

.ai-strip__key {
  color: var(--text-3);
}

.ai-strip__sep {
  width: 1px;
  height: 18px;
  background: var(--line);
}

.ai-tabs {
  align-self: flex-start;
  width: min(360px, 100%);
}

@media (max-width: 720px) {
  .ai-strip__sep {
    display: none;
  }
}
</style>
