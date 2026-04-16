<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { clampPageToTotal, usePagedItems, useResetPageOnChange } from '../composables/pagination'
import { PAGE_SIZES_LOGS, PAGE_SIZES_RICH } from '../constants/filters'
import { api } from '../services/api'
import { resolveErrorMessage } from '../utils/errors'
import type { AdminAiConfig, AdminAiDocument, AdminAiLog, AdminAiPerformanceView, AdminAiVersion } from '../types'

const loading = ref(false)
const savingConfig = ref(false)
const publishing = ref(false)
const clearingDocuments = ref(false)
const clearingLogs = ref(false)

const activeTab = ref<'control' | 'documents' | 'publish' | 'logs'>('control')
const documents = ref<AdminAiDocument[]>([])
const versions = ref<AdminAiVersion[]>([])
const logs = ref<AdminAiLog[]>([])
const performance = ref<AdminAiPerformanceView>({
  queryCount24h: 0,
  avgFirstTokenMs: 0,
  avgTotalLatencyMs: 0,
  avgRetrievalMs: 0,
  avgModelMs: 0,
  p95LatencyMs: 0,
  ragHitRate: 0,
  structuredHitRate: 0,
  webSearchRate: 0,
  cacheHitRate: 0,
  failureRate: 0,
})
const publishNotes = ref('')

const documentPage = ref(1)
const documentPageSize = ref(6)
const versionPage = ref(1)
const versionPageSize = ref(6)
const logPage = ref(1)
const logPageSize = ref(10)

const quickQuestionsText = ref('')
const blockedKeywordsText = ref('')

const config = reactive<AdminAiConfig>({
  base: {
    enabled: true,
    welcomeMessage: '你好，我是狼顾问，可以帮你解释规则、认识角色和推荐板子。',
    quickQuestions: ['12人进阶推荐什么板子', '女巫能不能自救', '守卫和女巫会不会冲突'],
    chatModel: '',
    embeddingModel: '',
    temperature: 0.25,
    maxSuggestions: 3,
  },
  prompt: {
    systemPrompt: '',
    recommendationPrompt: '',
    refusalPrompt: '',
  },
  retrieval: {
    topK: 3,
    similarityThreshold: 0.55,
    historyWindow: 4,
  },
  search: {
    webSearchEnabled: false,
    timeoutSeconds: 4,
    provider: 'MINIMAX_WEB_SEARCH',
  },
  safety: {
    unsupportedMessage: '',
    blockedKeywords: [],
  },
  ui: {
    mascot: 'wolf-head',
    dockLabel: 'AI战术顾问',
    accentColor: '#FFC000',
  },
})

const performanceCards = computed(() => [
  { label: '24h 问答量', value: `${performance.value.queryCount24h}`, hint: '最近 24 小时' },
  { label: '平均首字', value: `${performance.value.avgFirstTokenMs}ms`, hint: '首 token 速度' },
  { label: '平均总耗时', value: `${performance.value.avgTotalLatencyMs}ms`, hint: '完整回答' },
  { label: 'P95 延迟', value: `${performance.value.p95LatencyMs}ms`, hint: '波动参考' },
  { label: '结构化命中', value: `${performance.value.structuredHitRate}%`, hint: '规则/推荐快路径' },
  { label: '知识命中', value: `${performance.value.ragHitRate}%`, hint: 'RAG 召回' },
  { label: '缓存命中', value: `${performance.value.cacheHitRate}%`, hint: '热问题缓存' },
  { label: '失败率', value: `${performance.value.failureRate}%`, hint: '越低越好' },
])

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

const pagedDocuments = usePagedItems(documents, documentPage, documentPageSize)
const pagedVersions = usePagedItems(versions, versionPage, versionPageSize)
const pagedLogs = usePagedItems(logs, logPage, logPageSize)

useResetPageOnChange(documentPage, [documentPageSize])
useResetPageOnChange(versionPage, [versionPageSize])
useResetPageOnChange(logPage, [logPageSize])

function showAiError(error: unknown, fallback = 'AI 助手后台操作失败') {
  ElMessage.error(resolveErrorMessage(error, fallback))
}

watch(
  () => documents.value.length,
  (total) => {
    clampPageToTotal(documentPage, documentPageSize, total)
  },
)

watch(
  () => versions.value.length,
  (total) => {
    clampPageToTotal(versionPage, versionPageSize, total)
  },
)

watch(
  () => logs.value.length,
  (total) => {
    clampPageToTotal(logPage, logPageSize, total)
  },
)

function assignConfig(payload: AdminAiConfig) {
  Object.assign(config.base, payload.base)
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
    .split(/[\,\n，]/)
    .map((item) => item.trim())
    .filter(Boolean)
  config.safety.blockedKeywords = blockedKeywordsText.value
    .split(/[\,\n，]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function sourceTypeLabel(sourceType: string) {
  if (sourceType === 'STRUCTURED') return '结构化'
  if (sourceType === 'DOCUMENT') return '上传文档'
  return sourceType || '未知'
}

function statusTagType(status: string) {
  if (status === 'APPROVED' || status === 'READY') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'FAILED' || status === 'REJECTED') return 'danger'
  return 'info'
}

function processingStatusLabel(status: string) {
  if (status === 'READY') return '已完成'
  if (status === 'PENDING') return '处理中'
  if (status === 'FAILED') return '失败'
  return status || '未知'
}

function reviewStatusLabel(status: string) {
  if (status === 'APPROVED') return '已通过'
  if (status === 'PENDING') return '待审核'
  if (status === 'REJECTED') return '已驳回'
  return status || '未知'
}

function formatHitSources(items: string[]) {
  if (!items?.length) {
    return ['未命中来源']
  }
  return items
}

async function loadAll() {
  loading.value = true
  try {
    const [configData, documentData, versionData, logData, summaryData] = await Promise.all([
      api.getAiConfig(),
      api.getAiDocuments(),
      api.getAiVersions(),
      api.getAiLogs(),
      api.getAiSummary(),
    ])
    assignConfig(configData)
    documents.value = documentData
    versions.value = versionData
    logs.value = logData
    performance.value = summaryData
  } catch (error) {
    showAiError(error, 'AI 助手后台数据加载失败')
  } finally {
    loading.value = false
  }
}

function applyPreset(type: 'small-machine' | 'speed' | 'accuracy') {
  if (type === 'small-machine') {
    config.base.chatModel = 'MiniMax-M2.7-highspeed'
    config.base.embeddingModel = 'embo-01'
    config.base.temperature = 0.25
    config.retrieval.topK = 3
    config.retrieval.similarityThreshold = 0.55
    config.retrieval.historyWindow = 4
    config.search.webSearchEnabled = false
    config.search.timeoutSeconds = 4
    ElMessage.success('已应用小机型推荐配置')
    return
  }
  if (type === 'speed') {
    config.base.chatModel = 'MiniMax-M2.7-highspeed'
    config.base.embeddingModel = 'embo-01'
    config.base.temperature = 0.2
    config.retrieval.topK = 2
    config.retrieval.similarityThreshold = 0.6
    config.retrieval.historyWindow = 3
    config.search.webSearchEnabled = false
    config.search.timeoutSeconds = 4
    ElMessage.success('已应用高速优先配置')
    return
  }
  config.base.chatModel = 'MiniMax-M2.7'
  config.base.embeddingModel = 'embo-01'
  config.base.temperature = 0.3
  config.retrieval.topK = 4
  config.retrieval.similarityThreshold = 0.5
  config.retrieval.historyWindow = 6
  config.search.webSearchEnabled = true
  config.search.timeoutSeconds = 6
  ElMessage.success('已应用准确率优先配置')
}

async function saveConfig() {
  applyTextFields()
  savingConfig.value = true
  try {
    const saved = await api.saveAiConfig(JSON.parse(JSON.stringify(config)))
    assignConfig(saved)
    ElMessage.success('AI 助手配置已保存')
  } catch (error) {
    showAiError(error, 'AI 助手配置保存失败')
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
    showAiError(error, '知识文档上传失败')
    options.onError?.(Object.assign(new Error('upload failed'), { status: 0, method: 'post', url: '' }) as never)
  }
}

async function updateReview(document: AdminAiDocument, reviewStatus: string) {
  try {
    const result = await api.updateAiDocumentReview(document.id, reviewStatus)
    documents.value = documents.value.map((item) => (item.id === result.id ? result : item))
    ElMessage.success(`文档状态已更新为${reviewStatusLabel(reviewStatus)}`)
  } catch (error) {
    showAiError(error, '文档审核状态更新失败')
  }
}

async function reindex(document: AdminAiDocument) {
  try {
    const result = await api.reindexAiDocument(document.id)
    documents.value = documents.value.map((item) => (item.id === result.id ? result : item))
    ElMessage.success('索引已重建')
  } catch (error) {
    showAiError(error, '知识索引重建失败')
  }
}

async function clearDocuments() {
  try {
    await ElMessageBox.confirm(
      '将清空所有上传的知识文档，并同步移除它们的索引与发布关联。结构化板子和角色知识不会被删除。',
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
      showAiError(error, '清空上传文档失败')
    }
  } finally {
    clearingDocuments.value = false
  }
}

async function clearLogs() {
  try {
    await ElMessageBox.confirm(
      '将清空全部问答日志记录，该操作不可恢复。',
      '清空问答日志',
      {
        confirmButtonText: '确认清空',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    clearingLogs.value = true
    const removed = await api.clearAiLogs()
    ElMessage.success(removed ? `已清空 ${removed} 条问答日志` : '当前没有可清空的问答日志')
    await loadAll()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      showAiError(error, '清空问答日志失败')
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
    showAiError(error, '知识版本发布失败')
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
    showAiError(error, '知识版本回滚失败')
  }
}

onMounted(loadAll)
</script>

<template>
  <el-card
    class="panel-card"
    v-loading="loading"
    element-loading-text="正在同步 AI 助手控制台..."
    element-loading-background="rgba(8, 8, 8, 0.74)"
  >
    <div class="panel-actions">
      <el-button @click="loadAll">刷新数据</el-button>
    </div>

    <el-tabs v-model="activeTab" class="ai-tabs">
      <el-tab-pane label="控制台" name="control">
        <div class="preset-shell">
          <div class="preset-copy">
            <div class="knowledge-kicker">Production Presets</div>
            <h4>一键套用推荐配置</h4>
            <p>先用推荐值把小机型首响和稳定性拉起来，再按日志和性能概览微调。</p>
          </div>
          <div class="preset-actions">
            <el-button @click="applyPreset('small-machine')">小机型推荐</el-button>
            <el-button @click="applyPreset('speed')">高速优先</el-button>
            <el-button @click="applyPreset('accuracy')">准确率优先</el-button>
          </div>
        </div>

        <div class="performance-grid">
          <div v-for="card in performanceCards" :key="card.label" class="performance-card">
            <span class="performance-label">{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.hint }}</small>
          </div>
        </div>

        <div class="form-grid">
          <el-card class="sub-card">
            <template #header>基础配置</template>
            <el-form label-position="top">
              <el-form-item label="启用 AI 助手">
                <el-switch v-model="config.base.enabled" />
              </el-form-item>
              <el-form-item label="欢迎语">
                <el-input v-model="config.base.welcomeMessage" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item label="推荐问题（逗号或换行分隔）">
                <el-input v-model="quickQuestionsText" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item label="聊天模型">
                <el-input v-model="config.base.chatModel" />
              </el-form-item>
              <el-form-item label="Embedding 模型">
                <el-input v-model="config.base.embeddingModel" />
              </el-form-item>
              <el-form-item label="温度 / 推荐问题数">
                <div class="inline-grid">
                  <el-input-number v-model="config.base.temperature" :min="0" :max="1.5" :step="0.05" />
                  <el-input-number v-model="config.base.maxSuggestions" :min="1" :max="6" />
                </div>
              </el-form-item>
            </el-form>
          </el-card>

          <el-card class="sub-card">
            <template #header>Prompt 与检索</template>
            <el-form label-position="top">
              <el-form-item label="系统 Prompt">
                <el-input v-model="config.prompt.systemPrompt" type="textarea" :rows="4" />
              </el-form-item>
              <el-form-item label="推荐 Prompt">
                <el-input v-model="config.prompt.recommendationPrompt" type="textarea" :rows="4" />
              </el-form-item>
              <el-form-item label="拒答 Prompt">
                <el-input v-model="config.prompt.refusalPrompt" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item label="TopK / 阈值 / 历史窗口">
                <div class="inline-grid inline-grid--triple">
                  <el-input-number v-model="config.retrieval.topK" :min="1" :max="10" />
                  <el-input-number v-model="config.retrieval.similarityThreshold" :min="0" :max="1" :step="0.05" />
                  <el-input-number v-model="config.retrieval.historyWindow" :min="2" :max="20" />
                </div>
              </el-form-item>
            </el-form>
          </el-card>

          <el-card class="sub-card">
            <template #header>搜索与安全</template>
            <el-form label-position="top">
              <el-form-item label="允许联网搜索">
                <el-switch v-model="config.search.webSearchEnabled" />
              </el-form-item>
              <el-form-item label="搜索提供方">
                <el-input v-model="config.search.provider" />
              </el-form-item>
              <el-form-item label="超时秒数">
                <el-input-number v-model="config.search.timeoutSeconds" :min="3" :max="30" />
              </el-form-item>
              <el-form-item label="边界提示语">
                <el-input v-model="config.safety.unsupportedMessage" type="textarea" :rows="3" />
              </el-form-item>
              <el-form-item label="拦截关键词（逗号或换行分隔）">
                <el-input v-model="blockedKeywordsText" type="textarea" :rows="3" />
              </el-form-item>
            </el-form>
          </el-card>

          <el-card class="sub-card">
            <template #header>UI 配置</template>
            <el-form label-position="top">
              <el-form-item label="吉祥物">
                <el-input v-model="config.ui.mascot" />
              </el-form-item>
              <el-form-item label="贴边文案">
                <el-input v-model="config.ui.dockLabel" />
              </el-form-item>
              <el-form-item label="强调色">
                <el-input v-model="config.ui.accentColor" />
              </el-form-item>
            </el-form>
          </el-card>
        </div>

        <div class="actions-row">
          <el-button type="primary" :loading="savingConfig" @click="saveConfig">保存 AI 配置</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="知识库" name="documents">
        <div class="knowledge-shell">
          <div class="knowledge-hero">
            <div class="knowledge-upload-card">
              <div class="knowledge-upload-copy">
                <div class="knowledge-kicker">Knowledge Intake</div>
                <h4>上传知识文档</h4>
                <p>支持 PDF、Markdown 与 TXT。新文档会先进入待审核区，发布后才会进入线上问答召回。</p>
              </div>
              <div class="knowledge-upload-actions">
                <el-upload :show-file-list="false" :http-request="uploadKnowledge" accept=".pdf,.md,.markdown,.txt">
                  <el-button type="primary" size="large">上传 PDF / MD / TXT</el-button>
                </el-upload>
                <span class="knowledge-upload-tip">上传后会自动解析、切片并等待审核。</span>
              </div>
            </div>

            <div class="knowledge-stats">
              <div class="knowledge-stat-card">
                <span class="knowledge-stat-label">总文档</span>
                <strong>{{ documentSummary.total }}</strong>
                <small>当前全部知识资产</small>
              </div>
              <div class="knowledge-stat-card">
                <span class="knowledge-stat-label">待审核</span>
                <strong>{{ documentSummary.pending }}</strong>
                <small>等待运营确认</small>
              </div>
              <div class="knowledge-stat-card">
                <span class="knowledge-stat-label">已通过</span>
                <strong>{{ documentSummary.approved }}</strong>
                <small>可以进入发布池</small>
              </div>
              <div class="knowledge-stat-card">
                <span class="knowledge-stat-label">已发布</span>
                <strong>{{ documentSummary.published }}</strong>
                <small>线上可被召回</small>
              </div>
            </div>
          </div>

          <div class="knowledge-table-card">
            <div class="knowledge-section-header">
              <div>
                <div class="knowledge-kicker">Knowledge Queue</div>
                <h4>知识文档列表</h4>
              </div>
              <div class="knowledge-section-actions">
                <span class="knowledge-section-meta">共 {{ documents.length }} 条资产，其中上传文档 {{ uploadedDocumentCount }} 条</span>
                <el-button
                  text
                  type="warning"
                  :disabled="!uploadedDocumentCount"
                  :loading="clearingDocuments"
                  @click="clearDocuments"
                >
                  一键清空上传文档
                </el-button>
              </div>
            </div>

            <template v-if="documents.length">
              <div class="table-scroll-shell table-scroll-shell--knowledge">
                <el-table :data="pagedDocuments" class="knowledge-table wide-table wide-table--knowledge" empty-text="暂无知识文档">
                  <el-table-column prop="name" label="名称" min-width="220">
                    <template #default="{ row }">
                      <div class="document-name-cell">
                        <strong>{{ row.name }}</strong>
                        <span>{{ row.fileName || row.sourceKey }}</span>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="来源" width="120">
                    <template #default="{ row }">
                      <el-tag effect="plain">{{ sourceTypeLabel(row.sourceType) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="解析状态" width="126">
                    <template #default="{ row }">
                      <el-tag :type="statusTagType(row.processingStatus)" effect="dark">
                        {{ processingStatusLabel(row.processingStatus) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="审核状态" width="126">
                    <template #default="{ row }">
                      <el-tag :type="statusTagType(row.reviewStatus)" effect="dark">
                        {{ reviewStatusLabel(row.reviewStatus) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="切片数" width="92" align="center">
                    <template #default="{ row }">
                      <span class="document-chunk-count">{{ row.chunkCount }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="摘要" min-width="320" show-overflow-tooltip>
                    <template #default="{ row }">
                      <span class="document-summary">{{ row.summary || '暂无摘要，等待解析完成。' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="220" fixed="right">
                    <template #default="{ row }">
                      <div class="document-actions">
                        <el-button text type="primary" @click="updateReview(row, 'APPROVED')">通过</el-button>
                        <el-button text type="warning" @click="updateReview(row, 'REJECTED')">驳回</el-button>
                        <el-button text @click="reindex(row)">重建索引</el-button>
                      </div>
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div class="admin-table-footer">
                <div class="admin-table-total">共 {{ documents.length }} 条知识资产</div>
                <el-pagination
                  v-model:current-page="documentPage"
                  v-model:page-size="documentPageSize"
                  background
                  size="small"
                  layout="sizes, prev, pager, next"
                  :pager-count="5"
                  :page-sizes="PAGE_SIZES_RICH"
                  :total="documents.length"
                />
              </div>
            </template>

            <div v-else class="knowledge-empty-state">
              <div class="knowledge-empty-mark">W</div>
              <div class="knowledge-empty-copy">
                <h4>还没有知识文档</h4>
                <p>先上传规则说明、板子手册或 FAQ 文档，审核并发布后，AI 助手才能稳定回答站内问题。</p>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="发布中心" name="publish">
        <el-card class="sub-card">
          <template #header>发布操作</template>
          <div class="publish-box">
            <el-input
              v-model="publishNotes"
              type="textarea"
              :rows="3"
              placeholder="写一段本次发布说明，方便后续回滚和版本追踪。"
            />
            <el-button type="primary" :loading="publishing" @click="publishVersion">发布当前已审核文档</el-button>
          </div>
        </el-card>

        <div class="table-scroll-shell">
          <el-table :data="pagedVersions" class="wide-table wide-table--publish" empty-text="暂无发布版本">
            <el-table-column prop="versionName" label="版本" min-width="180" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.current ? 'warning' : 'info'">
                  {{ row.current ? '当前线上' : '历史版本' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发布人" width="120">
              <template #default="{ row }">{{ row.publishedBy || '系统' }}</template>
            </el-table-column>
            <el-table-column prop="notes" label="说明" min-width="280" show-overflow-tooltip />
            <el-table-column label="文档数" width="100">
              <template #default="{ row }">{{ row.documentIds.length }}</template>
            </el-table-column>
            <el-table-column prop="createTime" label="发布时间" width="180" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button text :disabled="row.current" @click="rollback(row.id)">回滚到此</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="versions.length" class="admin-table-footer">
          <div class="admin-table-total">共 {{ versions.length }} 个版本</div>
          <el-pagination
            v-model:current-page="versionPage"
            v-model:page-size="versionPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="PAGE_SIZES_RICH"
            :total="versions.length"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="问答日志" name="logs">
        <div class="logs-toolbar">
          <div class="logs-toolbar-copy">
            <div class="knowledge-kicker">Query Logs</div>
            <span class="knowledge-section-meta">共 {{ logs.length }} 条问答日志</span>
          </div>
          <el-button text type="warning" :disabled="!logs.length" :loading="clearingLogs" @click="clearLogs">
            一键清空问答日志
          </el-button>
        </div>

        <div class="performance-grid performance-grid--compact">
          <div v-for="card in performanceCards" :key="`logs-${card.label}`" class="performance-card">
            <span class="performance-label">{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.hint }}</small>
          </div>
        </div>

        <div class="table-scroll-shell">
          <el-table :data="pagedLogs" class="wide-table wide-table--logs" empty-text="暂无问答日志">
            <el-table-column prop="createTime" label="时间" width="180" />
            <el-table-column prop="openid" label="用户" width="180" show-overflow-tooltip />
            <el-table-column prop="userMessage" label="问题" min-width="280" show-overflow-tooltip />
            <el-table-column prop="answerType" label="答案类型" width="180" />
            <el-table-column label="命中来源" min-width="260">
              <template #default="{ row }">
                <div class="tag-row">
                  <el-tag v-for="item in formatHitSources(row.hitSources)" :key="item" effect="plain">{{ item }}</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="联网" width="80">
              <template #default="{ row }">
                <el-tag :type="row.usedWebSearch ? 'warning' : 'info'">
                  {{ row.usedWebSearch ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ row.latencyMs }}ms</template>
            </el-table-column>
            <el-table-column label="首字" width="90">
              <template #default="{ row }">{{ row.firstTokenMs }}ms</template>
            </el-table-column>
            <el-table-column label="检索" width="90">
              <template #default="{ row }">{{ row.retrievalMs }}ms</template>
            </el-table-column>
            <el-table-column label="向量" width="90">
              <template #default="{ row }">{{ row.embeddingMs }}ms</template>
            </el-table-column>
            <el-table-column label="模型" width="90">
              <template #default="{ row }">{{ row.modelMs }}ms</template>
            </el-table-column>
            <el-table-column label="联网检索" width="100">
              <template #default="{ row }">{{ row.webSearchMs }}ms</template>
            </el-table-column>
            <el-table-column label="缓存" width="84">
              <template #default="{ row }">
                <el-tag :type="row.cacheHit ? 'success' : 'info'">
                  {{ row.cacheHit ? '命中' : '未命中' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="streamMode" label="流式模式" width="110" />
            <el-table-column prop="fallbackMode" label="回退路径" width="140" show-overflow-tooltip />
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="row.success ? 'success' : 'danger'">
                  {{ row.success ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="failureType" label="失败类型" width="160" show-overflow-tooltip />
          </el-table>
        </div>

        <div v-if="logs.length" class="admin-table-footer">
          <div class="admin-table-total">共 {{ logs.length }} 条问答日志</div>
          <el-pagination
            v-model:current-page="logPage"
            v-model:page-size="logPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="PAGE_SIZES_LOGS"
            :total="logs.length"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<style scoped>
.panel-card {
  position: relative;
  border-radius: 18px;
  min-width: 0;
}

.panel-card :deep(.el-card__body) {
  padding-top: 10px;
}

.panel-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
  min-width: 0;
}

.panel-actions :deep(.el-button) {
  min-width: 116px;
  height: 36px;
  padding: 0 18px;
  border-radius: 12px;
  border-color: rgba(255, 192, 0, 0.22);
  background:
    radial-gradient(circle at top left, rgba(255, 203, 75, 0.08), transparent 58%),
    linear-gradient(180deg, rgba(39, 36, 28, 0.94), rgba(24, 22, 18, 0.98));
  color: #f1cf72;
  font-weight: 700;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.ai-tabs {
  display: grid;
  min-width: 0;
}

.ai-tabs :deep(.el-tabs__header) {
  position: sticky;
  top: var(--panel-sticky-offset, 0px);
  z-index: 15;
  margin: 0 -12px 14px;
  padding: 4px 12px 10px;
  padding-right: 148px;
  background:
    linear-gradient(180deg, rgba(18, 18, 18, 0.98), rgba(18, 18, 18, 0.94) 72%, rgba(18, 18, 18, 0.84));
  backdrop-filter: blur(14px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.ai-tabs :deep(.el-tabs__content) {
  position: relative;
  z-index: 1;
}

.ai-tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: rgba(255, 255, 255, 0.06);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.preset-shell {
  padding: 18px 20px;
  margin-bottom: 16px;
  border: 1px solid rgba(255, 192, 0, 0.12);
  border-radius: 18px;
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.12), transparent 34%),
    linear-gradient(180deg, rgba(28, 28, 28, 0.96), rgba(14, 14, 14, 0.96));
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
}

.preset-copy {
  display: grid;
  gap: 6px;
}

.preset-copy h4 {
  margin: 0;
  font-size: 22px;
}

.preset-copy p {
  margin: 0;
  color: #9d9d9d;
  line-height: 1.6;
}

.preset-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.performance-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}

.performance-grid--compact {
  margin-bottom: 14px;
}

.performance-card {
  padding: 16px;
  border-radius: 16px;
  border: 1px solid rgba(255, 192, 0, 0.1);
  background: linear-gradient(180deg, rgba(30, 30, 30, 0.94), rgba(18, 18, 18, 0.94));
  display: grid;
  gap: 6px;
}

.performance-card strong {
  font-size: 26px;
  line-height: 1;
  color: #ffc000;
}

.performance-label,
.performance-card small {
  color: #9d9d9d;
}

.performance-label {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.performance-card small {
  font-size: 12px;
}

.sub-card {
  border-radius: 16px;
}

.inline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
}

.inline-grid--triple {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.actions-row {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.knowledge-shell {
  display: grid;
  gap: 18px;
  min-width: 0;
}

.knowledge-hero {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.knowledge-upload-card {
  padding: 20px;
  border: 1px solid rgba(255, 192, 0, 0.12);
  border-radius: 18px;
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.12), transparent 36%),
    linear-gradient(180deg, rgba(28, 28, 28, 0.96), rgba(14, 14, 14, 0.96));
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  min-width: 0;
}

.knowledge-upload-copy,
.logs-toolbar-copy {
  display: grid;
  gap: 6px;
}

.knowledge-kicker {
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.knowledge-upload-copy h4,
.knowledge-empty-copy h4,
.knowledge-section-header h4 {
  margin: 0;
  font-size: 22px;
  line-height: 1.1;
}

.knowledge-upload-copy p,
.knowledge-empty-copy p {
  margin: 0;
  color: #9d9d9d;
  line-height: 1.6;
}

.knowledge-upload-actions {
  display: grid;
  justify-items: end;
  align-content: center;
  gap: 12px;
}

.knowledge-upload-tip {
  color: #9d9d9d;
  font-size: 12px;
  line-height: 1.5;
}

.knowledge-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  min-width: 0;
}

.knowledge-stat-card {
  padding: 18px;
  border-radius: 16px;
  border: 1px solid rgba(255, 192, 0, 0.1);
  background: linear-gradient(180deg, rgba(30, 30, 30, 0.94), rgba(18, 18, 18, 0.94));
  display: grid;
  gap: 6px;
}

.knowledge-stat-card strong {
  font-size: 28px;
  line-height: 1;
  color: #ffc000;
}

.knowledge-stat-label,
.knowledge-stat-card small,
.knowledge-section-meta,
.document-name-cell span,
.document-summary {
  color: #9d9d9d;
}

.knowledge-stat-label {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.knowledge-stat-card small {
  font-size: 12px;
}

.knowledge-table-card {
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.04);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.02);
  min-width: 0;
}

.knowledge-section-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  min-width: 0;
}

.knowledge-section-actions,
.logs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  flex-wrap: wrap;
}

.logs-toolbar {
  margin-bottom: 14px;
  padding: 16px 18px;
  border: 1px solid rgba(255, 255, 255, 0.04);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.02);
}

.knowledge-section-meta {
  font-size: 13px;
}

.document-name-cell {
  display: grid;
  gap: 4px;
}

.document-name-cell strong {
  color: #f3f3f3;
  font-size: 14px;
}

.document-name-cell span {
  font-size: 12px;
}

.document-chunk-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 34px;
  height: 34px;
  border-radius: 999px;
  background: rgba(255, 192, 0, 0.12);
  color: #ffc000;
  font-weight: 700;
}

.document-summary {
  line-height: 1.5;
}

.document-actions,
.tag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.publish-box {
  display: grid;
  gap: 12px;
}

.publish-box :deep(.el-button) {
  justify-self: end;
  width: auto;
  min-width: 220px;
  padding-inline: 24px;
}

.table-scroll-shell {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 8px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 192, 0, 0.42) rgba(255, 255, 255, 0.05);
}

.table-scroll-shell::-webkit-scrollbar {
  height: 8px;
}

.table-scroll-shell::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 999px;
}

.table-scroll-shell::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(255, 211, 92, 0.7), rgba(182, 126, 12, 0.72));
}

.table-scroll-shell :deep(.el-table) {
  min-width: 100%;
}

.table-scroll-shell :deep(.el-scrollbar__wrap) {
  overflow-x: hidden !important;
}

.table-scroll-shell :deep(.el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.table-scroll-shell--knowledge :deep(.el-table),
:deep(.wide-table--knowledge) {
  min-width: 1160px;
}

.table-scroll-shell :deep(.wide-table--publish) {
  min-width: 980px;
}

.table-scroll-shell :deep(.wide-table--logs) {
  min-width: 1980px;
}

.knowledge-empty-state {
  min-height: 280px;
  display: grid;
  place-items: center;
  gap: 16px;
  text-align: center;
  border: 1px dashed rgba(255, 192, 0, 0.18);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(24, 24, 24, 0.88), rgba(14, 14, 14, 0.96));
}

.knowledge-empty-mark {
  width: 74px;
  height: 74px;
  border-radius: 24px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(255, 192, 0, 0.2), rgba(255, 192, 0, 0.06));
  color: #ffc000;
  font-size: 32px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.knowledge-empty-copy {
  display: grid;
  gap: 10px;
  max-width: 460px;
  padding: 0 16px;
}

:deep(.knowledge-table .el-table__inner-wrapper::before) {
  background-color: rgba(255, 255, 255, 0.08);
}

:deep(.knowledge-table th.el-table__cell) {
  background: rgba(255, 255, 255, 0.02);
}

:deep(.knowledge-table .el-table__row:hover > td.el-table__cell) {
  background: rgba(255, 192, 0, 0.05);
}

@media (max-width: 1200px) {
  .preset-shell {
    grid-template-columns: 1fr;
  }

  .preset-actions {
    justify-content: flex-start;
  }

  .performance-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .knowledge-upload-card {
    grid-template-columns: 1fr;
  }

  .knowledge-upload-actions {
    justify-items: start;
  }

  .knowledge-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .panel-actions {
    position: static;
    margin-bottom: 12px;
    display: flex;
    justify-content: flex-end;
  }

  .ai-tabs :deep(.el-tabs__header) {
    position: static;
    top: auto;
    z-index: auto;
    margin: 0 0 18px;
    padding: 0;
    padding-right: 0;
    background: transparent;
    backdrop-filter: none;
    border-bottom: none;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }

  .performance-grid {
    grid-template-columns: 1fr;
  }

  .inline-grid,
  .inline-grid--triple {
    grid-template-columns: 1fr;
  }

  .knowledge-section-header,
  .knowledge-section-actions,
  .logs-toolbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .publish-box :deep(.el-button) {
    justify-self: stretch;
    min-width: 0;
  }
}
</style>

