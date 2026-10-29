<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiModel, ModelTestResult } from '../../types'
import { formatTime } from '../../utils/board'

// 模型管理：登记多个 OpenAI 兼容的对话模型，选一个「当前使用」，AI 助手问答和角色起草马上改用它
const models = ref<AdminAiModel[]>([])
const loading = ref(false)
const busyId = ref<number | null>(null)
const results = reactive<Record<number, ModelTestResult & { at: number }>>({})

const dialogOpen = ref(false)
const saving = ref(false)
const testingForm = ref(false)
const loadingRemote = ref(false)
const remoteModels = ref<string[]>([])
const formResult = ref<ModelTestResult | null>(null)
const form = reactive({ id: 0, name: '', baseUrl: '', model: '', apiKey: '', enabled: true, disableThinking: false, remark: '' })

const active = computed(() => models.value.find((model) => model.active) || null)

async function load() {
  loading.value = true
  try {
    models.value = await api.getAiModels()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模型列表加载失败')
  } finally {
    loading.value = false
  }
}

function openForm(model?: AdminAiModel) {
  form.id = model?.id || 0
  form.name = model?.name || ''
  form.baseUrl = model?.baseUrl || ''
  form.model = model?.model || ''
  form.apiKey = model?.apiKey || ''
  form.enabled = model?.enabled ?? true
  form.disableThinking = model?.disableThinking ?? false
  form.remark = model?.remark || ''
  remoteModels.value = []
  formResult.value = null
  dialogOpen.value = true
}

async function testSaved(model: AdminAiModel) {
  busyId.value = model.id
  try {
    results[model.id] = { ...(await api.testAiModel({ id: model.id })), at: Date.now() }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试失败')
  } finally {
    busyId.value = null
  }
}

async function activate(model: AdminAiModel) {
  busyId.value = model.id
  try {
    await api.activateAiModel(model.id)
    ElMessage.success(`已切换到「${model.name}」，AI 助手马上用它回答`)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '切换失败')
  } finally {
    busyId.value = null
  }
}

async function remove(model: AdminAiModel) {
  try {
    await ElMessageBox.confirm(`删除「${model.name}」？Key 也会一起删掉。`, '删除模型', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api.deleteAiModel(model.id)
    ElMessage.success('模型已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

function formPayload() {
  return { id: form.id || undefined, baseUrl: form.baseUrl, model: form.model, apiKey: form.apiKey, disableThinking: form.disableThinking }
}

async function testForm() {
  testingForm.value = true
  formResult.value = null
  try {
    formResult.value = await api.testAiModel(formPayload())
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试失败')
  } finally {
    testingForm.value = false
  }
}

async function loadRemote() {
  loadingRemote.value = true
  try {
    remoteModels.value = await api.listRemoteAiModels(formPayload())
    if (!remoteModels.value.length) ElMessage.info('接口没返回模型列表，手动填模型编码就行')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读不到模型列表')
  } finally {
    loadingRemote.value = false
  }
}

async function save() {
  if (!form.name.trim() || !form.baseUrl.trim() || !form.model.trim()) {
    ElMessage.warning('名称、接口地址和模型编码都要填')
    return
  }
  saving.value = true
  try {
    const payload = { name: form.name, baseUrl: form.baseUrl, model: form.model, apiKey: form.apiKey, enabled: form.enabled, disableThinking: form.disableThinking, remark: form.remark }
    if (form.id) await api.updateAiModel(form.id, payload)
    else await api.createAiModel(payload)
    ElMessage.success(form.id ? '模型已保存' : '模型已添加，测试通过后可以设为当前')
    dialogOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function resultText(result: ModelTestResult) {
  return result.ok ? `在线 · ${result.latencyMs} ms · 回复「${result.reply}」` : `不通：${result.error}`
}

onMounted(load)
</script>

<template>
  <section class="mm">
    <div class="mm__head">
      <div>
        <h3 class="mm__title">对话模型</h3>
        <p class="nt-muted">
          AI 助手问答和「按世界观起草」都用<strong>当前使用</strong>的那一个；接口按 OpenAI 兼容格式（{接口地址}/chat/completions）调用，Key 只存在服务器上。
        </p>
      </div>
      <button class="nt-btn nt-btn--gold" type="button" @click="openForm()">+ 添加模型</button>
    </div>

    <div v-if="active" class="mm__current">
      当前使用：<strong>{{ active.name }}</strong> · <code>{{ active.model }}</code>
    </div>
    <div v-else-if="!loading" class="mm__current mm__current--warn">还没有设为当前的模型，AI 助手会退回原来的 DeepSeek 配置</div>

    <p v-if="loading && !models.length" class="nt-empty">正在读取模型…</p>

    <div class="mm__grid">
      <article v-for="model in models" :key="model.id" class="mm__card" :class="{ 'mm__card--active': model.active, 'mm__card--off': !model.enabled }">
        <div class="mm__card-head">
          <strong class="mm__name">{{ model.name }}</strong>
          <span v-if="model.active" class="nt-badge nt-badge--on">当前使用</span>
          <span v-else-if="!model.enabled" class="nt-badge nt-badge--off">已停用</span>
        </div>
        <dl class="mm__meta">
          <div><dt>接口地址</dt><dd><code>{{ model.baseUrl }}</code></dd></div>
          <div><dt>模型编码</dt><dd><code>{{ model.model }}</code></dd></div>
          <div><dt>Key</dt><dd><code>{{ model.apiKey || '没填' }}</code></dd></div>
          <div><dt>思考</dt><dd>{{ model.disableThinking ? '已关闭，直接回答' : '默认（推理模型会先思考）' }}</dd></div>
          <div v-if="model.remark"><dt>备注</dt><dd>{{ model.remark }}</dd></div>
          <div v-if="model.updateTime"><dt>更新</dt><dd>{{ formatTime(model.updateTime) }}</dd></div>
        </dl>
        <p v-if="results[model.id]" class="mm__result" :class="results[model.id].ok ? 'ok' : 'bad'">{{ resultText(results[model.id]) }}</p>
        <div class="mm__actions">
          <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="busyId === model.id" @click="testSaved(model)">
            {{ busyId === model.id ? '测试中…' : '测试连接' }}
          </button>
          <button v-if="!model.active && model.enabled" class="nt-btn nt-btn--gold nt-btn--small" type="button" :disabled="busyId === model.id" @click="activate(model)">设为当前</button>
          <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" @click="openForm(model)">编辑</button>
          <button v-if="!model.active" class="nt-iconbtn mm__del" type="button" :aria-label="`删除${model.name}`" title="删除" @click="remove(model)">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
          </button>
        </div>
      </article>
    </div>

    <el-dialog v-model="dialogOpen" :title="form.id ? `编辑「${form.name}」` : '添加模型'" width="min(620px, 94vw)" append-to-body destroy-on-close>
      <div class="mf">
        <div class="nt-field">
          <label class="nt-label" for="mf-name">名称</label>
          <input id="mf-name" v-model="form.name" class="nt-input" maxlength="60" placeholder="比如：学校 DeepSeek" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="mf-url">接口地址 <small>· 到 /v1 为止，比如 https://example.com/v1</small></label>
          <input id="mf-url" v-model.trim="form.baseUrl" class="nt-input mono" maxlength="300" placeholder="https://…/v1" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="mf-key">API Key <small>{{ form.id ? '· 不改就保持原样' : '' }}</small></label>
          <input id="mf-key" v-model.trim="form.apiKey" class="nt-input mono" type="password" autocomplete="off" maxlength="500" placeholder="sk-…" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="mf-model">模型编码</label>
          <div class="mf__model">
            <input id="mf-model" v-model.trim="form.model" class="nt-input mono" list="mf-remote" maxlength="120" placeholder="比如：deepseek-chat" />
            <datalist id="mf-remote"><option v-for="id in remoteModels" :key="id" :value="id" /></datalist>
            <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="loadingRemote || !form.baseUrl" @click="loadRemote">{{ loadingRemote ? '读取中…' : '读取模型列表' }}</button>
          </div>
          <small v-if="remoteModels.length" class="mf__hint">读到 {{ remoteModels.length }} 个模型，点输入框可以直接选</small>
        </div>
        <div class="nt-field">
          <label class="nt-label" for="mf-remark">备注</label>
          <input id="mf-remark" v-model="form.remark" class="nt-input" maxlength="200" placeholder="可不填，比如：学校提供，限校内使用" />
        </div>
        <div class="mf__switches">
          <label class="nt-switch">
            <input v-model="form.enabled" type="checkbox" />
            <span class="nt-switch__track"></span>
            {{ form.enabled ? '启用' : '停用' }}
          </label>
          <label class="nt-switch">
            <input v-model="form.disableThinking" type="checkbox" />
            <span class="nt-switch__track"></span>
            关闭思考
          </label>
        </div>
        <p class="mf__hint">
          「关闭思考」给推理模型用（比如学校的 DeepSeek V4）：不开的话它每次要先想几十秒到几分钟才回答。官方 DeepSeek 不用开，开了对方可能不认这个参数。
        </p>
        <p v-if="formResult" class="mm__result" :class="formResult.ok ? 'ok' : 'bad'">{{ resultText(formResult) }}</p>
      </div>
      <template #footer>
        <div class="mf__footer">
          <button class="nt-btn nt-btn--ghost" type="button" :disabled="testingForm" @click="testForm">{{ testingForm ? '测试中…' : '测试连接' }}</button>
          <button class="nt-btn nt-btn--ghost" type="button" @click="dialogOpen = false">取消</button>
          <button class="nt-btn nt-btn--gold" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.mm {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mm__head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.mm__title {
  margin: 0 0 6px;
  font: 900 20px var(--serif);
}

.mm__current {
  padding: 12px 16px;
  border-radius: 14px;
  background: var(--ok-bg);
  color: var(--camp-villager);
  font-size: 14px;
}

.mm__current--warn {
  background: var(--warn-bg);
  color: var(--camp-god);
}

.mm__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 18px;
}

.mm__card {
  padding: 18px;
  border-radius: 18px;
  background: var(--panel-raised);
  border: 2px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mm__card--active {
  border-color: var(--moon);
}

.mm__card--off {
  opacity: 0.7;
}

.mm__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.mm__name {
  font: 900 18px var(--serif);
}

.mm__meta {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}

.mm__meta div {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 8px;
}

.mm__meta dt {
  color: var(--text-3);
}

.mm__meta dd {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}

code,
.mono {
  font-family: ui-monospace, Consolas, monospace;
}

.mm__result {
  margin: 0;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
}

.mm__result.ok {
  background: var(--ok-bg);
  color: var(--camp-villager);
}

.mm__result.bad {
  background: var(--danger-bg);
  color: #ffb1a9;
}

.mm__actions {
  margin-top: auto;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.mm__del {
  margin-left: auto;
  width: 36px;
  height: 36px;
  border-radius: 12px;
}

.mf {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mf__model {
  display: flex;
  gap: 10px;
}

.mf__hint {
  color: var(--text-3);
  font-size: 12px;
}

.mf__switches {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}

.mf__footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
