<script setup lang="ts">
import PageHeader from './ui/PageHeader.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { api } from '../services/api'
import type { AdminAiConfig, AdminAiDocument, AdminAiLog, AdminAiVersion } from '../types'
import AiAssistantControlTab from './aiAssistant/AiAssistantControlTab.vue'
import AiModelManager from './aiAssistant/AiModelManager.vue'
import AiAssistantDocumentsTab from './aiAssistant/AiAssistantDocumentsTab.vue'
import AiAssistantLogsTab from './aiAssistant/AiAssistantLogsTab.vue'
import AiAssistantPublishTab from './aiAssistant/AiAssistantPublishTab.vue'
import AiPlaygroundTab from './aiAssistant/AiPlaygroundTab.vue'
import {
  formatHitSources,
  processingStatusLabel,
  reviewStatusLabel,
  sourceTypeLabel,
  statusTagType,
} from './aiAssistant/formatters'
import { clampPage, paginate } from '../utils/pagination'

const loading = ref(false)
const savingConfig = ref(false)
const publishing = ref(false)
const clearingDocuments = ref(false)
const clearingLogs = ref(false)

const activeTab = ref<'control' | 'models' | 'playground' | 'documents' | 'publish' | 'logs'>('control')
const documents = ref<AdminAiDocument[]>([])
const versions = ref<AdminAiVersion[]>([])
const logs = ref<AdminAiLog[]>([])
const publishNotes = ref('')

const documentPage = ref(1)
const documentPageSize = ref(6)
const versionPage = ref(1)
const versionPageSize = ref(6)
const logPage = ref(1)
const logPageSize = ref(10)
const logFilter = ref('all')

const quickQuestionsText = ref('')
const blockedKeywordsText = ref('')

const config = reactive<AdminAiConfig>({
  base: {
    enabled: true,
    welcomeMessage: '你好，我是狼人杀 AI 助手，可以帮你解释规则、认识角色和推荐板子。',
    quickQuestions: ['12人进阶推荐什么板子？', '女巫能不能自救？', '守卫和女巫会不会冲突？'],
    temperature: 0.35,
    maxSuggestions: 3,
  },
  provider: {
    platform: 'DEEPSEEK',
    model: 'deepseek-v4-flash',
    baseUrl: 'https://api.deepseek.com',
    apiKey: '',
  },
  volcengine: {
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    embeddingModel: 'doubao-embedding-large-text-250515',
    embeddingApiKey: '',
    searchModel: 'doubao-seed-1-6-thinking-250715',
    searchApiKey: '',
  },
  prompt: {
    systemPrompt: '',
    recommendationPrompt: '',
    refusalPrompt: '',
  },
  retrieval: {
    topK: 4,
    similarityThreshold: 0.45,
    historyWindow: 12,
  },
  search: {
    webSearchEnabled: true,
  },
  safety: {
    unsupportedMessage: '',
    blockedKeywords: [],
  },
  ui: {
    mascot: 'wolf-head',
    dockLabel: 'AI 狼顾问',
    accentColor: '#FFC000',
  },
})

const documentSummary = computed(() => {
  const total = documents.value.length
  const approved = documents.value.filter((item) => item.reviewStatus === 'APPROVED').length
  const pending = documents.value.filter((item) => item.reviewStatus === 'PENDING').length
  const published = documents.value.filter((item) => item.publishVersionId != null).length
  return { total, approved, pending, published }
})

const uploadedDocumentCount = computed(() =>
  documents.value.filter((item) => item.sourceType === 'DOCUMENT').length,
)

const pagedDocuments = computed(() => paginate(documents.value, documentPage.value, documentPageSize.value))
const pagedVersions = computed(() => paginate(versions.value, versionPage.value, versionPageSize.value))
const filteredLogs = computed(() => {
  if (logFilter.value === 'failed') return logs.value.filter((item) => !item.success)
  if (logFilter.value === 'slow') return logs.value.filter((item) => item.latencyMs >= 10000)
  return logs.value
})
const pagedLogs = computed(() => paginate(filteredLogs.value, logPage.value, logPageSize.value))

watch(documentPageSize, () => {
  documentPage.value = 1
})

watch(versionPageSize, () => {
  versionPage.value = 1
})

watch([logPageSize, logFilter], () => {
  logPage.value = 1
})

watch(
  () => documents.value.length,
  (total) => {
    documentPage.value = clampPage(documentPage.value, documentPageSize.value, total)
  },
)

watch(
  () => versions.value.length,
  (total) => {
    versionPage.value = clampPage(versionPage.value, versionPageSize.value, total)
  },
)

watch(
  () => filteredLogs.value.length,
  (total) => {
    logPage.value = clampPage(logPage.value, logPageSize.value, total)
  },
)

function assignConfig(payload: AdminAiConfig) {
  Object.assign(config.base, payload.base)
  Object.assign(config.provider, payload.provider)
  Object.assign(config.volcengine, payload.volcengine)
  Object.assign(config.prompt, payload.prompt)
  Object.assign(config.retrieval, payload.retrieval)
  Object.assign(config.search, payload.search)
  Object.assign(config.safety, payload.safety)
  Object.assign(config.ui, payload.ui)
  syncTextFields()
}

function syncTextFields() {
  quickQuestionsText.value = config.base.quickQuestions.join('，')
  blockedKeywordsText.value = config.safety.blockedKeywords.join('，')
}

function applyTextFields() {
  config.base.quickQuestions = quickQuestionsText.value
    .split(/[,\n，]/)
    .map((item) => item.trim())
    .filter(Boolean)
  config.safety.blockedKeywords = blockedKeywordsText.value
    .split(/[,\n，]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

async function loadAll() {
  loading.value = true
  try {
    const [configData, documentData, versionData, logData] = await Promise.all([
      api.getAiConfig(),
      api.getAiDocuments(),
      api.getAiVersions(),
      api.getAiLogs(),
    ])
    assignConfig(configData)
    documents.value = documentData
    versions.value = versionData
    logs.value = logData
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 助手数据加载失败')
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  applyTextFields()
  savingConfig.value = true
  try {
    const saved = await api.saveAiConfig(JSON.parse(JSON.stringify(config)))
    assignConfig(saved)
    ElMessage.success('AI 助手配置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 助手配置保存失败')
  } finally {
    savingConfig.value = false
  }
}

async function uploadKnowledge(options: UploadRequestOptions) {
  try {
    const result = await api.uploadAiDocument(options.file as File)
    documents.value = [result, ...documents.value.filter((item) => item.id !== result.id)]
    documentPage.value = 1
    ElMessage.success('知识文档已上传')
    options.onSuccess?.(result)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '知识文档上传失败')
    options.onError?.(Object.assign(new Error('upload failed'), { status: 0, method: 'post', url: '' }) as never)
  }
}

async function updateReview(document: AdminAiDocument, reviewStatus: string) {
  try {
    const result = await api.updateAiDocumentReview(document.id, reviewStatus)
    documents.value = documents.value.map((item) => (item.id === result.id ? result : item))
    ElMessage.success(`文档状态已更新为 ${reviewStatusLabel(reviewStatus)}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审核状态更新失败')
  }
}

async function reindex(document: AdminAiDocument) {
  try {
    const result = await api.reindexAiDocument(document.id)
    documents.value = documents.value.map((item) => (item.id === result.id ? result : item))
    ElMessage.success('索引已重建')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '重建索引失败')
  }
}

async function clearDocuments() {
  try {
    await ElMessageBox.confirm(
      '将清空所有上传的知识文档，并同步移除它们的发布关联。结构化板子和角色知识不会被删除。',
      '清空上传文档',
      {
        confirmButtonText: '确认清空',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    clearingDocuments.value = true
    const removed = await api.clearAiDocuments()
    ElMessage.success(removed ? `已清空 ${removed} 条上传文档` : '当前没有可清空的上传文档')
    await loadAll()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '清空文档失败')
    }
  } finally {
    clearingDocuments.value = false
  }
}

async function clearLogs() {
  try {
    await ElMessageBox.confirm('将清空全部问答日志记录，该操作不可恢复。', '清空问答日志', {
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      type: 'warning',
    })
    clearingLogs.value = true
    const removed = await api.clearAiLogs()
    ElMessage.success(removed ? `已清空 ${removed} 条问答日志` : '当前没有可清空的问答日志')
    await loadAll()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '清空日志失败')
    }
  } finally {
    clearingLogs.value = false
  }
}

async function publishVersion() {
  publishing.value = true
  try {
    await api.publishAiVersion(publishNotes.value)
    publishNotes.value = ''
    ElMessage.success('新的知识版本已发布')
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

async function rollback(versionId: number) {
  try {
    await api.rollbackAiVersion(versionId)
    ElMessage.success('已回滚到所选版本')
    await loadAll()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '回滚失败')
  }
}

onMounted(loadAll)
</script>

<template>
  <PageHeader eyebrow="ASSISTANT" title="AI 助手" description="配置人设和模型，在调试台试答，管理知识库、发布版本和问答日志。">
    <template #actions>
      <button class="nt-btn nt-btn--ghost" type="button" :disabled="loading" @click="loadAll">{{ loading ? '同步中…' : '刷新数据' }}</button>
    </template>
  </PageHeader>

  <div
    class="ai-panel"
    v-loading="loading"
    element-loading-text="正在同步 AI 助手控制台…"
    element-loading-background="rgba(21, 18, 28, 0.74)"
  >
    <el-tabs v-model="activeTab" class="ai-tabs">
      <el-tab-pane label="控制台" name="control">
        <AiAssistantControlTab
          :config="config"
          :quick-questions-text="quickQuestionsText"
          :blocked-keywords-text="blockedKeywordsText"
          :saving-config="savingConfig"
          @update:quick-questions-text="quickQuestionsText = $event"
          @update:blocked-keywords-text="blockedKeywordsText = $event"
          @save="saveConfig"
        />
      </el-tab-pane>

      <el-tab-pane label="模型管理" name="models">
        <AiModelManager v-if="activeTab === 'models'" />
      </el-tab-pane>

      <el-tab-pane label="调试台" name="playground">
        <AiPlaygroundTab v-if="activeTab === 'playground'" />
      </el-tab-pane>

      <el-tab-pane label="知识库" name="documents">
        <AiAssistantDocumentsTab
          :documents="documents"
          :paged-documents="pagedDocuments"
          :document-summary="documentSummary"
          :uploaded-document-count="uploadedDocumentCount"
          :document-page="documentPage"
          :document-page-size="documentPageSize"
          :clearing-documents="clearingDocuments"
          :upload-request="uploadKnowledge"
          :source-type-label="sourceTypeLabel"
          :status-tag-type="statusTagType"
          :processing-status-label="processingStatusLabel"
          :review-status-label="reviewStatusLabel"
          @update:document-page="documentPage = $event"
          @update:document-page-size="documentPageSize = $event"
          @review="updateReview"
          @reindex="reindex"
          @clear="clearDocuments"
        />
      </el-tab-pane>

      <el-tab-pane label="发布中心" name="publish">
        <AiAssistantPublishTab
          :publish-notes="publishNotes"
          :publishing="publishing"
          :versions="versions"
          :paged-versions="pagedVersions"
          :version-page="versionPage"
          :version-page-size="versionPageSize"
          @update:publish-notes="publishNotes = $event"
          @update:version-page="versionPage = $event"
          @update:version-page-size="versionPageSize = $event"
          @publish="publishVersion"
          @rollback="rollback"
        />
      </el-tab-pane>

      <el-tab-pane label="问答日志" name="logs">
        <AiAssistantLogsTab
          :logs="filteredLogs"
          :total-logs="logs.length"
          :log-filter="logFilter"
          :paged-logs="pagedLogs"
          :log-page="logPage"
          :log-page-size="logPageSize"
          :clearing-logs="clearingLogs"
          :format-hit-sources="formatHitSources"
          @update:log-page="logPage = $event"
          @update:log-page-size="logPageSize = $event"
          @update:log-filter="logFilter = $event"
          @clear="clearLogs"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.ai-panel {
  position: relative;
  min-width: 0;
  padding: 8px 24px 24px;
  border-radius: 22px;
  background: var(--panel);
  border: 1px solid var(--panel-line);
}

.ai-tabs {
  min-width: 0;
}

.ai-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.ai-tabs :deep(.el-tabs__item) {
  height: 52px;
  font-size: 16px;
}

.ai-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: var(--panel-line);
}

@media (max-width: 720px) {
  .ai-panel {
    padding: 4px 14px 16px;
  }
}
</style>
