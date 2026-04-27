<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../services/api'
import type {
  AiAdminConfig,
  AiAdminDebugResponse,
  AiAdminDocument,
  AiAdminEvalCase,
  AiAdminLog,
  AiAdminPublish,
} from '../types'
import { resolveErrorMessage } from '../utils/errors'

const loading = ref(false)
const uploading = ref(false)
const publishing = ref(false)
const debugLoading = ref(false)
const rebuildLoading = ref(false)
const savingConfig = ref(false)

const config = reactive<AiAdminConfig>({
  enabled: true,
  knowledgeBaseEnabled: true,
  webSearchEnabled: true,
  postgresReady: false,
  chatReady: false,
  embeddingReady: false,
  topK: 8,
  minScore: 0.12,
  maxEvidenceChars: 6000,
  chatModel: 'MiniMax-M2.7',
  embeddingModel: 'embo-01',
  chatApiKeyMasked: null,
  embeddingApiKeyMasked: null,
})

const secretForm = reactive({
  chatApiKey: '',
  embeddingApiKey: '',
})

const uploadForm = reactive({
  domain: 'terms',
  title: '',
})

const selectedFile = ref<File | null>(null)
const publishDescription = ref('')
const debugQuery = ref('')
const debugResult = ref<AiAdminDebugResponse | null>(null)
const evalForm = reactive({
  question: '',
  expectedSubject: '',
  expectedKeywords: '',
  category: '',
})

const documents = ref<AiAdminDocument[]>([])
const publishVersions = ref<AiAdminPublish[]>([])
const logs = ref<AiAdminLog[]>([])
const evals = ref<AiAdminEvalCase[]>([])
const stats = ref<Record<string, number>>({})

const chatConfigured = computed(() => config.chatReady || Boolean(config.chatApiKeyMasked))
const embeddingConfigured = computed(() => config.embeddingReady || Boolean(config.embeddingApiKeyMasked))

const statusText = computed(() => {
  if (!config.enabled) {
    return '已关闭'
  }
  if (!config.knowledgeBaseEnabled) {
    return config.webSearchEnabled ? '联网回答' : '不可用'
  }
  return '知识库模式'
})

const uploadCount = computed(() => documents.value.filter((item) => item.sourceType === 'UPLOAD').length)

async function loadAll() {
  loading.value = true
  try {
    const [configData, docs, versions, logList, evalList, statData] = await Promise.all([
      api.getAiConfig(),
      api.getAiDocuments(),
      api.getAiPublishVersions(),
      api.getAiLogs(),
      api.getAiEvals(),
      api.getAiStats(),
    ])
    Object.assign(config, configData)
    documents.value = docs
    publishVersions.value = versions
    logs.value = logList
    evals.value = evalList
    stats.value = statData
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '加载 AI 管理数据失败'))
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  savingConfig.value = true
  try {
    const chatApiKey = secretForm.chatApiKey.trim()
    const embeddingApiKey = secretForm.embeddingApiKey.trim()
    const updated = await api.updateAiConfig({
      enabled: config.enabled,
      knowledgeBaseEnabled: config.knowledgeBaseEnabled,
      webSearchEnabled: config.webSearchEnabled,
      topK: config.topK,
      minScore: config.minScore,
      maxEvidenceChars: config.maxEvidenceChars,
      chatModel: config.chatModel,
      embeddingModel: config.embeddingModel,
      chatApiKey: chatApiKey || undefined,
      embeddingApiKey: embeddingApiKey || undefined,
    })
    Object.assign(config, updated)
    secretForm.chatApiKey = ''
    secretForm.embeddingApiKey = ''
    ElMessage.success(chatApiKey || embeddingApiKey ? '配置已更新，密钥已安全保存' : '配置已更新')
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '保存配置失败'))
  } finally {
    savingConfig.value = false
  }
}

function onFileChange(uploadFile: { raw?: File }) {
  selectedFile.value = uploadFile.raw ?? null
}

async function uploadDocument() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文档')
    return
  }
  uploading.value = true
  try {
    await api.uploadAiDocument(selectedFile.value, uploadForm.domain, uploadForm.title || undefined)
    selectedFile.value = null
    uploadForm.title = ''
    ElMessage.success('文档已上传并完成重建')
    await loadAll()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '上传失败'))
  } finally {
    uploading.value = false
  }
}

async function reindexDocument(documentUid: string) {
  try {
    await api.reindexAiDocument(documentUid)
    ElMessage.success('重建索引成功')
    await loadAll()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '重建索引失败'))
  }
}

async function importBusiness() {
  try {
    const result = await api.importBusinessAiKnowledge()
    ElMessage.success(`导入完成，共新增 ${result.imported} 条文档`)
    await loadAll()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '导入失败'))
  }
}

async function rebuildAll() {
  rebuildLoading.value = true
  try {
    await api.rebuildAi()
    ElMessage.success('全量重建完成')
    await loadAll()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '全量重建失败'))
  } finally {
    rebuildLoading.value = false
  }
}

async function publishCurrent() {
  publishing.value = true
  try {
    await api.publishAiVersion(publishDescription.value)
    publishDescription.value = ''
    ElMessage.success('发布成功')
    await loadAll()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '发布失败'))
  } finally {
    publishing.value = false
  }
}

async function runDebug() {
  if (!debugQuery.value.trim()) {
    ElMessage.warning('请输入调试查询')
    return
  }
  debugLoading.value = true
  try {
    debugResult.value = await api.debugAiRetrieve(debugQuery.value.trim())
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '调试检索失败'))
  } finally {
    debugLoading.value = false
  }
}

async function createEval() {
  if (!evalForm.question.trim()) {
    ElMessage.warning('请填写评测问题')
    return
  }
  try {
    await api.createAiEval({
      question: evalForm.question.trim(),
      expectedSubject: evalForm.expectedSubject || undefined,
      expectedKeywords: evalForm.expectedKeywords || undefined,
      category: evalForm.category || undefined,
    })
    evalForm.question = ''
    evalForm.expectedSubject = ''
    evalForm.expectedKeywords = ''
    evalForm.category = ''
    ElMessage.success('评测样例已添加')
    evals.value = await api.getAiEvals()
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '添加评测样例失败'))
  }
}

async function handleAction(action: string, row: AiAdminDocument) {
  if (action === 'reindex') {
    await reindexDocument(row.documentUid)
    return
  }
  if (action === 'inspect') {
    debugQuery.value = row.title
    await runDebug()
  }
}

async function confirmRebuildAll() {
  try {
    await ElMessageBox.confirm('这会清空并重建所有文档切片和向量索引，是否继续？', '确认操作', {
      confirmButtonText: '继续',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await rebuildAll()
  } catch {
    // ignore canceled
  }
}

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <section class="ai-shell" v-loading="loading">
    <el-card class="ai-summary-card">
      <div class="ai-summary-head">
        <div>
          <div class="ai-eyebrow">AI ASSISTANT CONTROL</div>
          <h2>狼人杀 AI 助手</h2>
          <p>当前模式：{{ statusText }}，文档 {{ documents.length }} 条，其中上传文档 {{ uploadCount }} 条。</p>
        </div>
        <div class="ai-summary-stats">
          <el-tag type="success">Postgres: {{ config.postgresReady ? 'READY' : 'DOWN' }}</el-tag>
          <el-tag type="warning">Chat: {{ chatConfigured ? 'READY' : 'MISSING_KEY' }}</el-tag>
          <el-tag type="info">Embedding: {{ embeddingConfigured ? 'READY' : 'MISSING_KEY' }}</el-tag>
        </div>
      </div>
      <div class="ai-summary-grid">
        <div class="ai-stat"><span>切片数</span><strong>{{ stats.chunks ?? 0 }}</strong></div>
        <div class="ai-stat"><span>向量数</span><strong>{{ stats.vectors ?? 0 }}</strong></div>
        <div class="ai-stat"><span>缺失向量</span><strong>{{ stats.missingVectors ?? 0 }}</strong></div>
        <div class="ai-stat"><span>孤儿向量</span><strong>{{ stats.orphanVectors ?? 0 }}</strong></div>
      </div>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">Prompt 与检索配置</div>
      </template>
      <div class="ai-config-grid">
        <el-form-item label="启用 AI">
          <el-switch v-model="config.enabled" />
        </el-form-item>
        <el-form-item label="启用知识库">
          <el-switch v-model="config.knowledgeBaseEnabled" />
        </el-form-item>
        <el-form-item label="启用联网">
          <el-switch v-model="config.webSearchEnabled" />
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="config.topK" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="最小分数">
          <el-input-number v-model="config.minScore" :min="0" :max="1" :step="0.01" />
        </el-form-item>
        <el-form-item label="证据上限">
          <el-input-number v-model="config.maxEvidenceChars" :min="1000" :max="12000" :step="200" />
        </el-form-item>
        <el-form-item label="Chat 模型">
          <el-input v-model="config.chatModel" />
        </el-form-item>
        <el-form-item label="Embedding 模型">
          <el-input v-model="config.embeddingModel" />
        </el-form-item>
        <el-form-item label="Chat API Key" class="ai-secret-item">
          <div class="ai-secret-field">
            <el-input
              v-model="secretForm.chatApiKey"
              type="password"
              show-password
              clearable
              autocomplete="new-password"
              :placeholder="config.chatApiKeyMasked || '留空则保留当前配置'"
            />
            <div class="config-hint">
              当前状态：{{ chatConfigured ? '已配置' : '未配置' }}。
              <span v-if="config.chatApiKeyMasked">已保存：{{ config.chatApiKeyMasked }}。</span>
              后台只回显掩码，不会返回明文。
            </div>
          </div>
        </el-form-item>
        <el-form-item label="Embedding API Key" class="ai-secret-item">
          <div class="ai-secret-field">
            <el-input
              v-model="secretForm.embeddingApiKey"
              type="password"
              show-password
              clearable
              autocomplete="new-password"
              :placeholder="config.embeddingApiKeyMasked || '留空则保留当前配置'"
            />
            <div class="config-hint">
              当前状态：{{ embeddingConfigured ? '已配置' : '未配置' }}。
              <span v-if="config.embeddingApiKeyMasked">已保存：{{ config.embeddingApiKeyMasked }}。</span>
              保存成功后输入框会自动清空。
            </div>
          </div>
        </el-form-item>
      </div>
      <div class="ai-config-note">
        安全说明：密钥仅在本次填写时写入存储；留空不会覆盖现有配置，前端也不会读取已保存的明文。
      </div>
      <div class="ai-actions">
        <el-button type="primary" :loading="savingConfig" @click="saveConfig">保存配置</el-button>
      </div>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">知识库上传与发布</div>
      </template>
      <div class="ai-upload-grid">
        <el-select v-model="uploadForm.domain" class="ai-upload-field">
          <el-option label="角色 roles" value="roles" />
          <el-option label="板子 boards" value="boards" />
          <el-option label="术语 terms" value="terms" />
          <el-option label="资讯 news" value="news" />
        </el-select>
        <el-input v-model="uploadForm.title" class="ai-upload-field" placeholder="文档标题（可选）" />
        <el-upload :auto-upload="false" :show-file-list="false" :on-change="onFileChange" accept=".md,.txt,.pdf">
          <el-button>选择文档</el-button>
        </el-upload>
        <el-button type="primary" :loading="uploading" @click="uploadDocument">上传并重建</el-button>
        <el-button @click="importBusiness">导入业务表</el-button>
        <el-button :loading="rebuildLoading" @click="confirmRebuildAll">全量重建</el-button>
      </div>
      <div v-if="selectedFile" class="file-hint">已选择：{{ selectedFile.name }}</div>
      <div class="publish-row">
        <el-input v-model="publishDescription" placeholder="发布说明（可选）" />
        <el-button type="warning" :loading="publishing" @click="publishCurrent">发布当前已审核文档</el-button>
      </div>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">知识文档列表</div>
      </template>
      <el-table :data="documents" stripe>
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="domain" label="领域" width="96" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="row.parseStatus === 'COMPLETED' ? 'success' : 'warning'">{{ row.parseStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="切片数" width="100" />
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-dropdown @command="(action: string) => handleAction(action, row)">
              <el-button size="small">操作</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="reindex">重建索引</el-dropdown-item>
                  <el-dropdown-item command="inspect">调试检索</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">发布记录</div>
      </template>
      <el-table :data="publishVersions" stripe>
        <el-table-column prop="versionKey" label="版本" min-width="220" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'">{{ row.active ? '当前线上' : row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="documentCount" label="文档数" width="100" />
        <el-table-column prop="chunkCount" label="切片数" width="100" />
        <el-table-column prop="activatedAt" label="发布时间" min-width="180" />
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">检索调试</div>
      </template>
      <div class="debug-row">
        <el-input v-model="debugQuery" placeholder="输入调试问题，例如：舞者技能" />
        <el-button type="primary" :loading="debugLoading" @click="runDebug">调试</el-button>
      </div>
      <div v-if="debugResult" class="debug-result">
        <div class="debug-meta">
          <el-tag>主体：{{ debugResult.subject || '无' }}</el-tag>
          <el-tag>意图：{{ debugResult.intent }}</el-tag>
          <el-tag :type="debugResult.outOfScope ? 'danger' : 'success'">{{ debugResult.outOfScope ? '超范围' : '范围内' }}</el-tag>
          <el-tag>命中：{{ debugResult.hits.length }}</el-tag>
        </div>
        <div class="debug-hits">
          <article v-for="hit in debugResult.hits" :key="hit.chunkUid" class="hit-item">
            <header>
              <strong>{{ hit.title }}</strong>
              <span>{{ hit.sourceType }} / {{ hit.score.toFixed(3) }}</span>
            </header>
            <p>{{ hit.content }}</p>
          </article>
        </div>
      </div>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">质量评测样例</div>
      </template>
      <div class="eval-create-row">
        <el-input v-model="evalForm.question" placeholder="评测问题" />
        <el-input v-model="evalForm.expectedSubject" placeholder="期望主体（可选）" />
        <el-input v-model="evalForm.expectedKeywords" placeholder="期望关键词（可选）" />
        <el-input v-model="evalForm.category" placeholder="分类（可选）" />
        <el-button type="primary" @click="createEval">新增样例</el-button>
      </div>
      <el-table :data="evals" stripe>
        <el-table-column prop="question" label="问题" min-width="260" show-overflow-tooltip />
        <el-table-column prop="expectedSubject" label="期望主体" width="120" />
        <el-table-column prop="expectedKeywords" label="期望关键词" min-width="200" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="120" />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">问答日志</div>
      </template>
      <el-table :data="logs" stripe>
        <el-table-column prop="question" label="问题" min-width="260" show-overflow-tooltip />
        <el-table-column prop="answerType" label="回答类型" width="160" />
        <el-table-column prop="subject" label="主体" width="120" />
        <el-table-column prop="retrievalMode" label="检索模式" width="140" />
        <el-table-column prop="hitCount" label="命中" width="80" />
        <el-table-column prop="latencyMs" label="耗时(ms)" width="110" />
        <el-table-column prop="failureReason" label="失败原因" min-width="160" />
        <el-table-column prop="createdAt" label="时间" min-width="180" />
      </el-table>
    </el-card>
  </section>
</template>

<style scoped>
.ai-shell {
  display: grid;
  gap: 16px;
}

.ai-summary-card h2 {
  margin: 8px 0;
  font-size: 28px;
}

.ai-summary-card p {
  margin: 0;
  color: #9a9a9a;
}

.ai-eyebrow {
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  font-weight: 900;
}

.ai-summary-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.ai-summary-stats {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  flex-wrap: wrap;
}

.ai-summary-grid {
  margin-top: 14px;
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
}

.ai-stat {
  padding: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  display: grid;
  gap: 6px;
}

.ai-stat span {
  color: #8d8d8d;
  font-size: 12px;
}

.ai-stat strong {
  font-size: 22px;
}

.card-header {
  font-weight: 800;
}

.ai-config-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.ai-secret-item :deep(.el-form-item__content) {
  align-items: stretch;
}

.ai-secret-item {
  grid-column: 1 / -1;
}

.ai-secret-field {
  width: 100%;
  display: grid;
  gap: 8px;
}

.config-hint {
  color: #9a9a9a;
  font-size: 12px;
  line-height: 1.5;
}

.ai-config-note {
  margin-top: 4px;
  color: #b7b7b7;
  font-size: 13px;
  line-height: 1.6;
}

.ai-actions {
  margin-top: 12px;
}

.ai-upload-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  align-items: center;
}

.ai-upload-field {
  width: 100%;
}

.file-hint {
  margin-top: 8px;
  color: #b7b7b7;
  font-size: 13px;
}

.publish-row {
  margin-top: 12px;
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) 240px;
}

.debug-row {
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) 120px;
}

.debug-result {
  margin-top: 12px;
  display: grid;
  gap: 10px;
}

.debug-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.debug-hits {
  display: grid;
  gap: 10px;
}

.hit-item {
  padding: 12px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.03);
}

.hit-item header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.hit-item p {
  margin: 8px 0 0;
  line-height: 1.6;
  color: #b7b7b7;
}

.eval-create-row {
  display: grid;
  gap: 12px;
  grid-template-columns: 2fr 1fr 1fr 1fr auto;
  margin-bottom: 12px;
}

@media (max-width: 980px) {
  .publish-row,
  .debug-row,
  .eval-create-row {
    grid-template-columns: 1fr;
  }
}
</style>
