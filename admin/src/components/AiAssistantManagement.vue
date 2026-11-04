<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../services/api'
import type { AdminAiConfig, AdminAiDocument, AdminAiLog, AdminAiModel, AdminAiVersion, AdminConfigVersion } from '../types'
import { go, route } from '../utils/route'
import { aiPath, currentVersion, formatLogTime, mergeSection, onlineCount, parseAiPath, type AiPage } from '../utils/aiAdmin'
import ComposePanel from './aiAssistant/ComposePanel.vue'
import LogsPanel from './aiAssistant/LogsPanel.vue'
import KnowledgePanel from './aiAssistant/KnowledgePanel.vue'
import DataPanel from './aiAssistant/DataPanel.vue'
import { draftChangeCount } from './aiAssistant/composeDraft'

// AI 助手页：左边是它自己的子菜单（编排、问答日志、资料库、数据），右边是当前页。
// 这里只管取数、开关、底部的版本小卡和切页，各页的事在各自的组件里
export type AiReload = 'config' | 'models' | 'documents' | 'versions' | 'logs' | 'configVersions'

const loading = ref(false)
const config = ref<AdminAiConfig | null>(null)
const models = ref<AdminAiModel[]>([])
const documents = ref<AdminAiDocument[]>([])
const versions = ref<AdminAiVersion[]>([])
const configVersions = ref<AdminConfigVersion[]>([])
const logs = ref<AdminAiLog[]>([])
const now = ref(new Date())
const switching = ref(false)
// 底部小卡「历史版本 ›」：让编排页打开历史版本抽屉（带时间戳，连点也能触发）
const historyRequest = ref(0)

const location = computed(() => parseAiPath(route.value.aiParts))
const MENU: { key: AiPage; label: string }[] = [
  { key: 'compose', label: '编排' },
  { key: 'logs', label: '问答日志' },
  { key: 'knowledge', label: '资料库' },
  { key: 'data', label: '数据' },
]

const knowledgeVersion = computed(() => currentVersion(versions.value))
const online = computed(() => onlineCount(documents.value, knowledgeVersion.value?.id ?? null))
const liveConfigVersion = computed(() => configVersions.value[0] || null)
const counts = computed<Record<AiPage, string>>(() => ({
  compose: draftChangeCount.value ? `${draftChangeCount.value} 处` : '',
  logs: String(logs.value.length),
  knowledge: String(online.value),
  data: '',
}))

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
  configVersions: async () => {
    configVersions.value = await api.getAiConfigVersions()
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

// 开关立即生效、不走草稿：紧急关掉时要马上起作用。以服务器配置为底只改这一个字段
async function toggleEnabled() {
  if (!config.value || switching.value) return
  const enabled = !config.value.base.enabled
  switching.value = true
  try {
    // 以服务器上最新的设置为底只改开关，别把别处刚发布的设置、刚换的 Key 带回旧值
    const fresh = await api.getAiConfig()
    const next = { ...fresh, base: { ...fresh.base, enabled } }
    config.value = await api.saveAiConfig(mergeSection(fresh, next, ['base.enabled']))
    ElMessage.success(config.value.base.enabled ? 'AI 助手已开启' : 'AI 助手已关闭，小程序里不能提问了')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '切换失败')
  } finally {
    switching.value = false
  }
}

function openHistory() {
  go(aiPath('compose'))
  historyRequest.value = Date.now()
}

// 有没发布的草稿时，刷新 / 关页面前提醒一次；草稿本来就存着，在后台里切页面不用提醒
function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (draftChangeCount.value > 0) {
    event.preventDefault()
    event.returnValue = ''
  }
}
watch(
  draftChangeCount,
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
  <div class="ai-shell" v-loading="loading && !config" element-loading-background="rgba(21, 18, 28, 0.6)">
    <aside class="ai-sub" aria-label="AI 助手菜单">
      <div class="ai-app">
        <span class="ai-app__icon" aria-hidden="true">狼</span>
        <div class="ai-app__text">
          <strong>AI 狼顾问</strong>
          <label class="nt-switch ai-app__switch">
            <input type="checkbox" :checked="config?.base.enabled ?? false" :disabled="!config || switching" @change="toggleEnabled" />
            <span class="nt-switch__track"></span>
            {{ !config ? '…' : config.base.enabled ? '已开启' : '已关闭' }}
          </label>
        </div>
      </div>

      <nav class="ai-menu">
        <button
          v-for="item in MENU"
          :key="item.key"
          type="button"
          class="ai-menu__item"
          :class="{ 'is-on': location.page === item.key }"
          :aria-current="location.page === item.key ? 'page' : undefined"
          @click="go(aiPath(item.key))"
        >
          <span>{{ item.label }}</span>
          <span class="ai-menu__count" :class="{ 'is-warn': item.key === 'compose' && draftChangeCount }">{{ counts[item.key] }}</span>
        </button>
      </nav>

      <div class="ai-card">
        <span class="ai-card__label">设置 · 线上版本</span>
        <strong v-if="liveConfigVersion">v{{ liveConfigVersion.id }} · {{ formatLogTime(liveConfigVersion.createTime, now) }}</strong>
        <strong v-else>还没发布过版本</strong>
        <span class="ai-card__label">资料 · {{ knowledgeVersion ? `线上 ${online} 份` : '还没发布过' }}</span>
        <button type="button" class="ai-card__link" @click="openHistory">历史版本 ›</button>
      </div>
    </aside>

    <section class="ai-page">
      <ComposePanel
        v-if="location.page === 'compose'"
        :config="config"
        :models="models"
        :documents="documents"
        :knowledge-version="knowledgeVersion"
        :config-versions="configVersions"
        :history-request="historyRequest"
        @published="reload('config', 'models', 'configVersions')"
        @config-saved="config = $event"
        @models-changed="reload('models')"
        @history-consumed="historyRequest = 0"
      />
      <LogsPanel v-else-if="location.page === 'logs'" :logs="logs" :documents="documents" :now="now" @reload="reload" />
      <KnowledgePanel
        v-else-if="location.page === 'knowledge'"
        :tab="location.knowledgeTab"
        :documents="documents"
        :versions="versions"
        :now="now"
        @reload="reload"
      />
      <DataPanel v-else :logs="logs" :now="now" />
    </section>
  </div>
</template>

<style scoped>
/* 抵掉主区的上下内边距，让子菜单从顶到底 */
.ai-shell {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  margin: -32px -40px -56px;
  min-height: 100vh;
}

.ai-sub {
  position: sticky;
  top: 0;
  align-self: start;
  height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 24px 12px 20px;
  border-right: 1px solid var(--line);
  background: #17131f;
}

.ai-app {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 6px 16px;
}

.ai-app__icon {
  width: 42px;
  height: 42px;
  flex: none;
  border-radius: 12px;
  background: #3b2f52;
  border: 2px solid var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  font: 900 18px var(--serif);
  color: var(--gold);
}

.ai-app__text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.ai-app__switch {
  font-size: 12.5px;
  color: var(--text-2);
}

.ai-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ai-menu__item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-2);
  font: 700 14.5px var(--sans);
  text-align: left;
  cursor: pointer;
}

.ai-menu__item:hover {
  color: var(--text);
}

.ai-menu__item.is-on {
  background: var(--panel-raised);
  color: var(--text);
  box-shadow: inset 3px 0 0 var(--gold);
}

.ai-menu__count {
  color: var(--text-3);
  font-size: 12px;
  font-weight: 600;
}

.ai-menu__count.is-warn {
  color: var(--camp-god);
}

.ai-card {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--panel);
  font-size: 13px;
}

.ai-card__label {
  color: var(--text-3);
  font-size: 12px;
}

.ai-card__label + strong {
  margin-bottom: 6px;
}

.ai-card__link {
  align-self: flex-start;
  margin-top: 4px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--moon);
  font: 600 12.5px var(--sans);
  cursor: pointer;
}

.ai-page {
  min-width: 0;
}

@media (max-width: 899px) {
  .ai-shell {
    grid-template-columns: 1fr;
    margin: -32px -16px -56px;
  }

  .ai-sub {
    position: static;
    height: auto;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }

  .ai-menu {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .ai-card {
    display: none;
  }
}
</style>
