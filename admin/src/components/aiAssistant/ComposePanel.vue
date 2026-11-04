<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiConfig, AdminAiDocument, AdminAiModel, AdminAiVersion, AdminConfigVersion } from '../../types'
import { go } from '../../utils/route'
import {
  DRAFT_FIELDS,
  KEY_FIELDS,
  aiPath,
  applyVersionToDraft,
  clampAdvanced,
  cloneConfig,
  configChanges,
  groupStructured,
  mergeSection,
  pendingPublish,
  rebaseDraft,
} from '../../utils/aiAdmin'
import { clearDraft, draftChangeCount, pendingQuestion, restoreDraft, writeDraft, type ComposeDraft } from './composeDraft'
import PreviewChat from './PreviewChat.vue'
import VersionDrawer from './VersionDrawer.vue'
import AiModelManager from './AiModelManager.vue'

// 编排：左边改设置（草稿），右边用草稿试答，点「发布」才对玩家生效，每次发布记一个版本
const props = defineProps<{
  config: AdminAiConfig | null
  models: AdminAiModel[]
  documents: AdminAiDocument[]
  knowledgeVersion: AdminAiVersion | null
  configVersions: AdminConfigVersion[]
  historyRequest: number
}>()
const emit = defineEmits<{ published: []; 'config-saved': [config: AdminAiConfig]; 'models-changed': []; 'history-consumed': [] }>()

const draft = ref<ComposeDraft | null>(null)
const modelMenuOpen = ref(false)
const modelDialogOpen = ref(false)
const historyOpen = ref(false)
const publishOpen = ref(false)
const publishing = ref(false)
const notes = ref('')
const newQuestion = ref('')
const newKeyword = ref('')
const keyInputs = ref({ embedding: '', search: '' })
// 管理员在 Key 框里打过字：别处的保存刷新设置时不覆盖它
const keysTouched = ref(false)
const savingKeys = ref(false)
const expandedChange = ref<string | null>(null)

/** 后端真正在用的模型：设为当前、启用、填了 Key，否则退回默认 DeepSeek 配置 */
const liveModel = computed(() => props.models.find((model) => model.active && model.enabled && model.apiKey) || null)
const usableModels = computed(() => props.models.filter((model) => model.enabled && model.apiKey))
const liveVersionId = computed(() => props.configVersions[0]?.id ?? null)

function modelName(id: number | null) {
  if (id == null) return null
  return props.models.find((model) => model.id === id)?.name || `模型 #${id}`
}

const draftModelUsable = computed(() => draft.value?.modelId == null || usableModels.value.some((model) => model.id === draft.value?.modelId))
// 数字按保存时的范围收好再比、再拿去试答：填了 50 的 TopK 发布时存的是 10，清单和预览也按 10
const effectiveConfig = computed(() => (props.config && draft.value ? clampAdvanced(props.config, draft.value.config) : null))
const changes = computed(() =>
  props.config && draft.value && effectiveConfig.value
    ? configChanges(
        props.config,
        effectiveConfig.value,
        liveModel.value?.name ?? null,
        modelName(draft.value.modelId),
        liveModel.value?.id ?? null,
        draft.value.modelId,
      )
    : [],
)
// 草稿不知道从哪个版本改起（版本列表比设置晚到）时不提醒
const staleBase = computed(
  () =>
    changes.value.length > 0 &&
    liveVersionId.value != null &&
    draft.value?.baseVersionId != null &&
    draft.value.baseVersionId !== liveVersionId.value,
)

const structured = computed(() => groupStructured(props.documents))
const uploads = computed(() => props.documents.filter((doc) => doc.sourceType === 'DOCUMENT'))
const pendingUploads = computed(() => pendingPublish(props.documents, props.knowledgeVersion?.id ?? null))

function freshDraft(live: AdminAiConfig): ComposeDraft {
  return {
    config: cloneConfig(live),
    modelId: liveModel.value?.id ?? null,
    baseConfig: cloneConfig(live),
    baseModelId: liveModel.value?.id ?? null,
    baseVersionId: liveVersionId.value,
  }
}

// 第一次拿到线上设置时从浏览器里恢复草稿；之后线上变了（开关、保存 Key、别处发布），没动过的字段跟上新值
watch(
  () => props.config,
  (live) => {
    if (!live) return
    // 正在输入的 Key 不被别处的保存（比如切开关）冲掉
    if (!keysTouched.value) {
      keyInputs.value = { embedding: live.volcengine.embeddingApiKey || '', search: live.volcengine.searchApiKey || '' }
    }
    if (!draft.value) {
      draft.value = restoreDraft(live, liveModel.value?.id ?? null, liveVersionId.value)
      return
    }
    const current = draft.value
    draft.value = {
      config: rebaseDraft(current.baseConfig, live, current.config, DRAFT_FIELDS),
      modelId: current.modelId === current.baseModelId ? liveModel.value?.id ?? null : current.modelId,
      baseConfig: cloneConfig(live),
      baseModelId: liveModel.value?.id ?? null,
      baseVersionId: current.baseVersionId,
    }
  },
  { immediate: true },
)

// 版本列表比设置晚到：草稿还没改过时补上它是从哪个版本开始的
watch(liveVersionId, (id) => {
  if (draft.value && draft.value.baseVersionId == null && !changes.value.length) draft.value.baseVersionId = id
})

// 模型列表可能比设置晚到，线上模型也可能在「管理模型」里变了：草稿没换过模型时一直跟着线上走
watch(
  () => liveModel.value?.id ?? null,
  (liveId) => {
    const current = draft.value
    if (!current || current.modelId !== current.baseModelId) return
    current.modelId = liveId
    current.baseModelId = liveId
  },
)

// 草稿一改就存进浏览器；改回和线上一样时清掉，不算草稿
watch(
  [draft, changes],
  () => {
    draftChangeCount.value = changes.value.length
    if (!draft.value || !props.config) return
    if (changes.value.length) writeDraft(draft.value, props.config)
    else clearDraft()
  },
  { deep: true, immediate: true },
)

// 子菜单卡片「历史版本 ›」：可能是从别的页跳过来、刚挂上就带着请求，所以要 immediate，用完告诉父页面清掉
watch(
  () => props.historyRequest,
  (value) => {
    if (!value) return
    historyOpen.value = true
    emit('history-consumed')
  },
  { immediate: true },
)

function pickModel(id: number | null) {
  if (draft.value) draft.value.modelId = id
  modelMenuOpen.value = false
}

function clearIncoming() {
  pendingQuestion.value = null
}

function openModelManager() {
  modelMenuOpen.value = false
  modelDialogOpen.value = true
}

// 推荐问题、拦截词的小标签：回车或失焦就加一条
function addItem(kind: 'question' | 'keyword') {
  if (!draft.value) return
  const text = kind === 'question' ? newQuestion : newKeyword
  const list = kind === 'question' ? draft.value.config.base.quickQuestions : draft.value.config.safety.blockedKeywords
  const value = text.value.trim()
  if (!value) return
  if (list.includes(value)) ElMessage.info('已经有这一条了')
  else list.push(value)
  text.value = ''
}

function onChipKeydown(event: KeyboardEvent, kind: 'question' | 'keyword') {
  if (event.key === 'Enter' && !event.isComposing) {
    event.preventDefault()
    addItem(kind)
  }
}

async function discard() {
  if (!props.config) return
  try {
    await ElMessageBox.confirm('放弃没发布的改动，回到线上的设置？', '放弃改动', {
      confirmButtonText: '放弃',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  draft.value = freshDraft(props.config)
}

function openPublish() {
  notes.value = ''
  expandedChange.value = null
  publishOpen.value = true
}

async function publish() {
  if (!props.config || !draft.value) return
  publishing.value = true
  try {
    // 只交草稿里的字段，开关和 Key 用线上的（Key 是打码值，原样传回表示不改）
    const ready = mergeSection(props.config, clampAdvanced(props.config, draft.value.config), DRAFT_FIELDS)
    const result = await api.publishAiConfig({ config: ready, modelId: draft.value.modelId, notes: notes.value.trim() })
    clearDraft()
    draft.value = {
      config: cloneConfig(result.config),
      modelId: draft.value.modelId,
      baseConfig: cloneConfig(result.config),
      baseModelId: draft.value.modelId,
      baseVersionId: result.version.id,
    }
    publishOpen.value = false
    ElMessage.success(`已发布 v${result.version.id}，玩家现在用的就是这一版`)
    emit('published')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

function applyVersion(version: AdminConfigVersion) {
  if (!props.config || !draft.value) return
  draft.value.config = applyVersionToDraft(version.config, props.config)
  // 版本里没有模型 = 当时用的是默认 DeepSeek 配置
  const modelOk = version.modelId == null || usableModels.value.some((model) => model.id === version.modelId)
  if (modelOk) draft.value.modelId = version.modelId
  historyOpen.value = false
  ElMessage.success(
    modelOk || version.modelId == null
      ? `已把 v${version.id} 放进草稿，点「发布」才生效`
      : `已把 v${version.id} 放进草稿；它用的模型「${version.modelName}」现在不能用，模型没换`,
  )
}

// 火山方舟的 Key 不走草稿、不进版本：单独保存、立即生效，浏览器里不留明文 Key
async function saveKeys() {
  if (!props.config) return
  savingKeys.value = true
  try {
    // 以服务器上最新的设置为底，只换这两个 Key，别把别处刚改的开关、设置带回旧值
    const fresh = await api.getAiConfig()
    const next = cloneConfig(fresh)
    next.volcengine.embeddingApiKey = keyInputs.value.embedding.trim()
    next.volcengine.searchApiKey = keyInputs.value.search.trim()
    const saved = await api.saveAiConfig(mergeSection(fresh, next, KEY_FIELDS))
    keyInputs.value = { embedding: saved.volcengine.embeddingApiKey || '', search: saved.volcengine.searchApiKey || '' }
    keysTouched.value = false
    emit('config-saved', saved)
    ElMessage.success('Key 已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Key 保存失败')
  } finally {
    savingKeys.value = false
  }
}

const keysChanged = computed(
  () =>
    props.config != null &&
    (keyInputs.value.embedding.trim() !== (props.config.volcengine.embeddingApiKey || '') ||
      keyInputs.value.search.trim() !== (props.config.volcengine.searchApiKey || '')),
)
</script>

<template>
  <div class="cp">
    <header class="cp-top">
      <div class="cp-top__title">
        <h1>编排</h1>
        <span v-if="changes.length" class="ai-tag ai-tag--warn">有 {{ changes.length }} 处改动没发布</span>
        <span v-else class="cp-muted">和线上一致</span>
      </div>
      <div class="cp-top__actions">
        <div class="cp-model">
          <button type="button" class="cp-model__btn" :aria-expanded="modelMenuOpen" @click="modelMenuOpen = !modelMenuOpen">
            <span class="cp-model__name">{{ modelName(draft?.modelId ?? null) || '默认 DeepSeek 配置' }}</span>
            <span v-if="!draftModelUsable" class="ai-tag ai-tag--bad">不能用了</span>
            <span aria-hidden="true">▾</span>
          </button>
          <div v-if="modelMenuOpen" class="cp-menu">
            <button
              v-for="model in usableModels"
              :key="model.id"
              type="button"
              class="cp-menu__item"
              :class="{ 'is-on': model.id === draft?.modelId }"
              @click="pickModel(model.id)"
            >
              <span>{{ model.name }}<small>{{ model.model }}</small></span>
              <span v-if="model.id === liveModel?.id" class="ai-tag ai-tag--moon">线上</span>
            </button>
            <p v-if="!usableModels.length" class="cp-muted cp-menu__empty">还没有可用的模型（要启用并填了 Key）</p>
            <button type="button" class="cp-menu__item cp-menu__manage" @click="openModelManager">管理模型…</button>
          </div>
        </div>
        <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="historyOpen = true">历史版本</button>
        <button v-if="changes.length" type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="discard">放弃改动</button>
        <button type="button" class="nt-btn nt-btn--gold nt-btn--small" :disabled="!changes.length || !draftModelUsable" @click="openPublish">发布</button>
      </div>
    </header>
    <p v-if="staleBase" class="cp-stale">
      线上已经有更新的版本 v{{ liveVersionId }}，你的改动是从 {{ draft?.baseVersionId ? `v${draft.baseVersionId}` : '更早的设置' }} 改起的；没动过的地方已经跟上了新版本。
    </p>

    <div v-if="!draft || !config" class="nt-empty cp-loading">正在读取设置…</div>
    <div v-else class="cp-work">
      <div class="cp-form">
        <section class="cp-sec">
          <label class="cp-label" for="cp-prompt">人设（系统提示词）</label>
          <textarea id="cp-prompt" v-model="draft.config.prompt.systemPrompt" class="nt-input cp-tall"></textarea>
          <span class="cp-hint">回答格式、只用站内资料、不替玩家判狼这些规矩已经内置，这里只写语气和身份。</span>
        </section>

        <section class="cp-sec">
          <label class="cp-label" for="cp-welcome">开场白</label>
          <textarea id="cp-welcome" v-model="draft.config.base.welcomeMessage" class="nt-input" rows="2" maxlength="300"></textarea>
        </section>

        <section class="cp-sec">
          <span class="cp-label">推荐问题 <small>· 玩家点一下就问</small></span>
          <div class="cp-chips">
            <span v-for="(item, index) in draft.config.base.quickQuestions" :key="item" class="cp-chip">
              {{ item }}
              <button type="button" :aria-label="`删除推荐问题 ${item}`" @click="draft.config.base.quickQuestions.splice(index, 1)">×</button>
            </span>
            <input
              v-model="newQuestion"
              class="cp-chip-input"
              maxlength="60"
              placeholder="＋ 加一个，回车"
              aria-label="新的推荐问题"
              @keydown="onChipKeydown($event, 'question')"
              @blur="addItem('question')"
            />
          </div>
        </section>

        <section class="cp-sec">
          <div class="cp-row">
            <span class="cp-label">资料</span>
            <button type="button" class="cp-link" @click="go(aiPath('knowledge'))">去资料库 ›</button>
          </div>
          <div class="cp-cards">
            <div class="cp-card">
              <span class="cp-muted">自动资料</span>
              <strong>{{ structured.boards.length + structured.roles.length + structured.world.length }} 份</strong>
              <span class="cp-muted">板子 {{ structured.boards.length }} · 角色 {{ structured.roles.length }} · 世界观 {{ structured.world.length }}</span>
            </div>
            <div class="cp-card">
              <span class="cp-muted">上传文档</span>
              <strong>{{ uploads.length }} 份</strong>
              <span class="cp-muted">{{ pendingUploads.length ? `${pendingUploads.length} 份待发布` : '没有待发布的' }}</span>
            </div>
            <div class="cp-card">
              <span class="cp-muted">检索</span>
              <strong>TopK {{ draft.config.retrieval.topK }}</strong>
              <span class="cp-muted">相似度 {{ draft.config.retrieval.similarityThreshold }}</span>
            </div>
          </div>
        </section>

        <section class="cp-sec">
          <span class="cp-label">拦截与婉拒 <small>· 问题里带这些词就不走模型，直接用下面的话婉拒</small></span>
          <div class="cp-chips">
            <span v-for="(item, index) in draft.config.safety.blockedKeywords" :key="item" class="cp-chip">
              {{ item }}
              <button type="button" :aria-label="`删除拦截词 ${item}`" @click="draft.config.safety.blockedKeywords.splice(index, 1)">×</button>
            </span>
            <input
              v-model="newKeyword"
              class="cp-chip-input"
              maxlength="30"
              placeholder="＋ 加一个，回车"
              aria-label="新的拦截词"
              @keydown="onChipKeydown($event, 'keyword')"
              @blur="addItem('keyword')"
            />
          </div>
          <textarea v-model="draft.config.safety.unsupportedMessage" class="nt-input" rows="2" aria-label="婉拒话术"></textarea>
        </section>

        <section class="cp-sec">
          <label class="cp-label" for="cp-rec">推荐板子时的要求 <small>· 候选板子由程序按人数、难度、标签和阵容先挑好，模型只负责介绍</small></label>
          <textarea id="cp-rec" v-model="draft.config.prompt.recommendationPrompt" class="nt-input" rows="3"></textarea>
        </section>

        <details class="cp-more">
          <summary>高级</summary>
          <div class="cp-grid">
            <label class="nt-field"><span class="cp-label">TopK <small>· 每次最多带几段资料</small></span><input v-model.number="draft.config.retrieval.topK" class="nt-input" type="number" min="1" max="10" step="1" /></label>
            <label class="nt-field"><span class="cp-label">相似度门槛 <small>· 0 ~ 1</small></span><input v-model.number="draft.config.retrieval.similarityThreshold" class="nt-input" type="number" min="0" max="1" step="0.05" /></label>
            <label class="nt-field"><span class="cp-label">历史窗口 <small>· 带上最近几条对话</small></span><input v-model.number="draft.config.retrieval.historyWindow" class="nt-input" type="number" min="2" max="20" step="1" /></label>
            <label class="nt-field"><span class="cp-label">温度 <small>· 越高越发散，0 ~ 1.5</small></span><input v-model.number="draft.config.base.temperature" class="nt-input" type="number" min="0" max="1.5" step="0.05" /></label>
            <label class="nt-field"><span class="cp-label">推荐问题数 <small>· 1 ~ 6</small></span><input v-model.number="draft.config.base.maxSuggestions" class="nt-input" type="number" min="1" max="6" step="1" /></label>
            <label class="nt-switch cp-switch">
              <input v-model="draft.config.search.webSearchEnabled" type="checkbox" />
              <span class="nt-switch__track"></span>
              联网搜索兜底
            </label>
          </div>
          <div class="cp-keys">
            <div class="cp-row">
              <span class="cp-label">火山方舟 <small>· Key 不走草稿，单独保存、马上生效</small></span>
            </div>
            <p class="cp-hint">向量检索要服务器开了 pgvector 才用；没开时按关键词检索。Base URL {{ config.volcengine.baseUrl || '—' }} · Embedding {{ config.volcengine.embeddingModel || '—' }} · 联网搜索 {{ config.volcengine.searchModel || '—' }}</p>
            <div class="cp-grid">
              <label class="nt-field"><span class="cp-label">Embedding Key</span><input v-model="keyInputs.embedding" class="nt-input cp-mono" @input="keysTouched = true" type="password" autocomplete="off" placeholder="没填" /></label>
              <label class="nt-field"><span class="cp-label">联网搜索 Key</span><input v-model="keyInputs.search" class="nt-input cp-mono" @input="keysTouched = true" type="password" autocomplete="off" placeholder="没填" /></label>
            </div>
            <button type="button" class="nt-btn nt-btn--ghost nt-btn--small cp-keys__save" :disabled="!keysChanged || savingKeys" @click="saveKeys">
              {{ savingKeys ? '保存中…' : '保存 Key' }}
            </button>
          </div>
        </details>
      </div>

      <PreviewChat
        class="cp-preview"
        :draft="effectiveConfig || draft.config"
        :model-id="draft.modelId"
        :incoming="pendingQuestion"
        @incoming-used="clearIncoming"
      />
    </div>

    <el-dialog v-model="publishOpen" title="发布设置" width="min(620px, 94vw)" append-to-body>
      <div class="cp-publish">
        <p class="cp-muted">发布后玩家马上用新设置，同时记成一个版本，以后可以退回。</p>
        <ul class="cp-changes">
          <li v-for="change in changes" :key="change.field">
            <span class="cp-label">{{ change.label }}</span>
            <template v-if="change.long">
              <button type="button" class="cp-link" @click="expandedChange = expandedChange === change.field ? null : change.field">
                {{ expandedChange === change.field ? '收起' : '改了，展开看前后' }}
              </button>
              <div v-if="expandedChange === change.field" class="cp-long">
                <div><span class="cp-muted">线上</span><p>{{ change.before }}</p></div>
                <div><span class="cp-muted">草稿</span><p>{{ change.after }}</p></div>
              </div>
            </template>
            <span v-else><del>{{ change.before }}</del> → <ins>{{ change.after }}</ins></span>
          </li>
        </ul>
        <label class="nt-field">
          <span class="cp-label">发布说明 <small>· 可不填</small></span>
          <input v-model="notes" class="nt-input" maxlength="200" placeholder="比如：换成学校的模型，拦截「代打」" />
        </label>
      </div>
      <template #footer>
        <div class="cp-dialog-foot">
          <button type="button" class="nt-btn nt-btn--ghost" @click="publishOpen = false">再改改</button>
          <button type="button" class="nt-btn nt-btn--gold" :disabled="publishing" @click="publish">{{ publishing ? '发布中…' : '确认发布' }}</button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="modelDialogOpen" title="管理模型" width="min(980px, 96vw)" append-to-body>
      <AiModelManager @changed="emit('models-changed')" />
    </el-dialog>

    <VersionDrawer
      v-if="historyOpen && config"
      :versions="configVersions"
      :live="config"
      :live-model-name="liveModel?.name ?? null"
      :draft-changes="changes.length"
      :live-version-id="liveVersionId"
      :live-model-id="liveModel?.id ?? null"
      @close="historyOpen = false"
      @apply="applyVersion"
    />
  </div>
</template>

<style scoped>
.cp {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.cp-top {
  flex: none;
  min-height: 64px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--line);
}

.cp-top__title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cp-top__title h1 {
  margin: 0;
  font: 900 22px var(--serif);
}

.cp-top__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cp-stale {
  margin: 0;
  padding: 8px 24px;
  background: var(--warn-bg);
  color: var(--camp-god);
  font-size: 13px;
  border-bottom: 1px solid var(--warn-line);
}

.cp-loading {
  margin: 24px;
}

.cp-model {
  position: relative;
}

.cp-model__btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  max-width: 280px;
  padding: 0 12px;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--text);
  font: 600 13px var(--sans);
  cursor: pointer;
}

.cp-model__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cp-menu {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: 10;
  width: 300px;
  padding: 6px;
  border-radius: 14px;
  background: var(--panel-raised);
  border: 2px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cp-menu__item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--text);
  font: 13px var(--sans);
  text-align: left;
  cursor: pointer;
}

.cp-menu__item small {
  display: block;
  color: var(--text-3);
  font-size: 11.5px;
}

.cp-menu__item:hover,
.cp-menu__item.is-on {
  background: var(--panel);
}

.cp-menu__manage {
  border-top: 1px solid var(--line);
  border-radius: 0 0 9px 9px;
  color: var(--moon);
}

.cp-menu__empty {
  padding: 8px 10px;
  margin: 0;
}

.cp-work {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 460px);
}

.cp-form {
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px 40px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.cp-preview {
  min-height: 0;
  border-left: 1px solid var(--line);
}

.cp-sec {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cp-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.cp-label {
  color: var(--text-2);
  font-size: 13px;
  font-weight: 700;
}

.cp-label small {
  font-weight: 400;
  color: var(--text-3);
}

.cp-hint,
.cp-muted {
  margin: 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.cp-tall {
  min-height: 130px;
}

.cp-link {
  border: 0;
  background: transparent;
  color: var(--moon);
  font: 600 12.5px var(--sans);
  cursor: pointer;
  padding: 0;
  align-self: flex-start;
}

.cp-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--bg);
}

.cp-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 4px 3px 11px;
  border-radius: 999px;
  background: var(--panel-raised);
  font-size: 13px;
}

.cp-chip button {
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-3);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.cp-chip button:hover {
  background: var(--danger-bg);
  color: var(--camp-wolf);
}

.cp-chip-input {
  flex: 1;
  min-width: 140px;
  height: 30px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font: 14px var(--sans);
}

.cp-chip-input::placeholder {
  color: var(--moon);
}

.cp-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.cp-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 14px;
  border-radius: 14px;
  background: var(--panel);
  font-size: 13px;
}

.cp-more {
  border-top: 1px solid var(--line);
  padding-top: 14px;
}

.cp-more summary {
  cursor: pointer;
  font: 900 15px var(--serif);
  color: var(--text-2);
}

.cp-more[open] summary {
  margin-bottom: 14px;
}

.cp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 14px;
  align-items: end;
}

.cp-switch {
  min-height: 44px;
  font-size: 13.5px;
}

.cp-keys {
  margin-top: 18px;
  padding: 14px 16px;
  border-radius: 14px;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cp-keys__save {
  align-self: flex-end;
}

.cp-mono {
  font-family: ui-monospace, monospace;
}

.cp-publish {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.cp-changes {
  list-style: none;
  margin: 0;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--bg);
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13.5px;
}

.cp-changes li {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cp-long {
  display: grid;
  gap: 8px;
}

.cp-long p {
  margin: 2px 0 0;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--panel);
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

.cp-dialog-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 1100px) {
  .cp {
    height: auto;
  }

  .cp-work {
    grid-template-columns: 1fr;
  }

  .cp-form {
    overflow: visible;
  }

  .cp-preview {
    height: 640px;
    border-left: 0;
    border-top: 1px solid var(--line);
  }

  .cp-cards {
    grid-template-columns: 1fr;
  }
}
</style>
