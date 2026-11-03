<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import type { AdminAiConfig } from '../../types'
import { go } from '../../utils/route'
import { ADVANCED_FIELDS, PERSONA_FIELDS, aiPath, clampAdvanced, cloneConfig, mergeSection, rebaseDraft, type SettingsSection } from '../../utils/aiAdmin'
import AiModelManager from './AiModelManager.vue'
import { advancedChanges, advancedDraft, personaChanges, personaDraft, serverConfig } from './settingsDrafts'

// 设置块：话术与人设 / 模型 / 高级。前后两个小节各有一份草稿、各自保存：以服务器上的配置为底只换自己的字段，
// 另一小节没保存的改动不会被带上，也不会被冲掉。原来的「控制台」和「模型管理」
const props = defineProps<{
  config: AdminAiConfig | null
  section: SettingsSection
}>()
const emit = defineEmits<{ saved: [config: AdminAiConfig]; 'models-changed': [] }>()

const SECTIONS: { key: SettingsSection; label: string; hint: string }[] = [
  { key: 'persona', label: '话术与人设', hint: '开关、欢迎语、人设、拦截' },
  { key: 'models', label: '模型', hint: '登记、测试、切换' },
  { key: 'advanced', label: '高级', hint: '检索、联网、火山' },
]

const saving = ref<'persona' | 'advanced' | null>(null)
const newQuestion = ref('')
const newKeyword = ref('')

// 第一次拿到配置时建草稿；之后服务器配置刷新了（比如别的标签页保存过），草稿里没动过的字段跟新值走，
// 管理员动过的留着——不然旧的打码 Key 会被当成改动提交上去
watch(
  () => props.config,
  (config) => {
    if (!config) return
    const previous = serverConfig.value
    const next = cloneConfig(config)
    personaDraft.value = previous && personaDraft.value ? rebaseDraft(previous, next, personaDraft.value, PERSONA_FIELDS) : cloneConfig(next)
    advancedDraft.value = previous && advancedDraft.value ? rebaseDraft(previous, next, advancedDraft.value, ADVANCED_FIELDS) : cloneConfig(next)
    serverConfig.value = next
  },
  { immediate: true },
)

const persona = computed(() => personaDraft.value)
const advanced = computed(() => advancedDraft.value)

// 推荐问题、拦截词的小标签：输入框回车或失焦就加一条
function addItem(kind: 'question' | 'keyword') {
  const draft = personaDraft.value
  if (!draft) return
  const text = kind === 'question' ? newQuestion : newKeyword
  const list = kind === 'question' ? draft.base.quickQuestions : draft.safety.blockedKeywords
  const value = text.value.trim()
  if (!value) return
  if (list.includes(value)) {
    ElMessage.info('已经有这一条了')
  } else {
    list.push(value)
  }
  text.value = ''
}

function onChipKeydown(event: KeyboardEvent, kind: 'question' | 'keyword') {
  if (event.key === 'Enter' && !event.isComposing) {
    event.preventDefault()
    addItem(kind)
  }
}

async function save(which: 'persona' | 'advanced') {
  const draft = which === 'persona' ? personaDraft.value : advancedDraft.value
  if (!serverConfig.value || !draft) return
  saving.value = which
  try {
    // 高级里的数字收进范围再交（后端不校验，温度过大模型会直接报错）
    const ready = which === 'advanced' ? clampAdvanced(serverConfig.value, draft) : draft
    const payload = mergeSection(serverConfig.value, ready, which === 'persona' ? PERSONA_FIELDS : ADVANCED_FIELDS)
    const saved = await api.saveAiConfig(payload)
    serverConfig.value = cloneConfig(saved)
    // 只重置这一小节的草稿，另一小节的改动留着
    if (which === 'persona') personaDraft.value = cloneConfig(saved)
    else advancedDraft.value = cloneConfig(saved)
    emit('saved', saved)
    ElMessage.success(which === 'persona' ? '话术与人设已保存' : '高级设置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = null
  }
}

async function discard(which: 'persona' | 'advanced') {
  try {
    await ElMessageBox.confirm('放弃这一小节没保存的改动，回到服务器上的设置？', '放弃改动', {
      confirmButtonText: '放弃',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  if (!serverConfig.value) return
  if (which === 'persona') personaDraft.value = cloneConfig(serverConfig.value)
  else advancedDraft.value = cloneConfig(serverConfig.value)
}
</script>

<template>
  <div class="st-wrap">
  <div class="st">
    <nav class="st-nav" aria-label="设置小节">
      <button
        v-for="item in SECTIONS"
        :key="item.key"
        type="button"
        class="st-nav__item"
        :class="{ 'is-on': section === item.key }"
        :aria-current="section === item.key ? 'page' : undefined"
        @click="go(aiPath('settings', item.key))"
      >
        <span>
          {{ item.label }}
          <span v-if="item.key === 'persona' && personaChanges" class="st-dot" :title="`${personaChanges} 处没保存`"></span>
          <span v-if="item.key === 'advanced' && advancedChanges" class="st-dot" :title="`${advancedChanges} 处没保存`"></span>
        </span>
        <small>{{ item.hint }}</small>
      </button>
    </nav>

    <section v-if="section === 'models'" class="nt-panel">
      <AiModelManager @changed="emit('models-changed')" />
    </section>

    <div v-else-if="!persona || !advanced" class="nt-empty">正在读取设置…</div>

    <section v-else-if="section === 'persona'" class="nt-panel st-form">
      <label class="nt-switch st-full st-enable">
        <input v-model="persona.base.enabled" type="checkbox" />
        <span class="nt-switch__track"></span>
        <span>
          <strong>{{ persona.base.enabled ? '已开启 AI 助手' : 'AI 助手已关闭' }}</strong>
          <small class="st-hint">关掉后小程序里不能提问</small>
        </span>
      </label>

      <div class="nt-field st-full">
        <label class="nt-label" for="st-welcome">欢迎语</label>
        <textarea id="st-welcome" v-model="persona.base.welcomeMessage" class="nt-input" rows="2" maxlength="300"></textarea>
      </div>

      <div class="nt-field st-full">
        <span class="nt-label">推荐问题 <small>· 小程序里点一下就问</small></span>
        <div class="st-chips">
          <span v-for="(item, index) in persona.base.quickQuestions" :key="item" class="st-chip">
            {{ item }}
            <button type="button" :aria-label="`删除推荐问题 ${item}`" @click="persona.base.quickQuestions.splice(index, 1)">×</button>
          </span>
          <input
            v-model="newQuestion"
            class="st-chip-input"
            maxlength="60"
            placeholder="+ 加一个，回车"
            aria-label="新的推荐问题"
            @keydown="onChipKeydown($event, 'question')"
            @blur="addItem('question')"
          />
        </div>
      </div>

      <div class="nt-field">
        <label class="nt-label" for="st-prompt">人设（系统 Prompt） <small>· 只写语气和身份，回答格式、只用站内资料这些规矩已内置</small></label>
        <textarea id="st-prompt" v-model="persona.prompt.systemPrompt" class="nt-input st-tall"></textarea>
      </div>

      <div class="nt-field">
        <label class="nt-label" for="st-rec">推荐板子时的补充要求 <small>· 候选板子由程序按人数、难度、标签和阵容先挑好，模型只负责介绍</small></label>
        <textarea id="st-rec" v-model="persona.prompt.recommendationPrompt" class="nt-input st-tall"></textarea>
      </div>

      <div class="nt-field">
        <span class="nt-label">拦截词 <small>· 问题里带这些词就不走模型，直接用右边的话婉拒</small></span>
        <div class="st-chips">
          <span v-for="(item, index) in persona.safety.blockedKeywords" :key="item" class="st-chip">
            {{ item }}
            <button type="button" :aria-label="`删除拦截词 ${item}`" @click="persona.safety.blockedKeywords.splice(index, 1)">×</button>
          </span>
          <input
            v-model="newKeyword"
            class="st-chip-input"
            maxlength="30"
            placeholder="+ 加一个，回车"
            aria-label="新的拦截词"
            @keydown="onChipKeydown($event, 'keyword')"
            @blur="addItem('keyword')"
          />
        </div>
      </div>

      <div class="nt-field">
        <label class="nt-label" for="st-refuse">婉拒话术</label>
        <textarea id="st-refuse" v-model="persona.safety.unsupportedMessage" class="nt-input" rows="3"></textarea>
      </div>

      <div class="st-save st-full">
        <span v-if="personaChanges" class="st-changes">改了 {{ personaChanges }} 处没保存</span>
        <button v-if="personaChanges" type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="discard('persona')">放弃改动</button>
        <button type="button" class="nt-btn nt-btn--ghost" @click="go(aiPath('qa'))">去试答几题</button>
        <button type="button" class="nt-btn nt-btn--gold" :disabled="!personaChanges || saving === 'persona'" @click="save('persona')">
          {{ saving === 'persona' ? '保存中…' : '保存话术与人设' }}
        </button>
      </div>
    </section>

    <section v-else class="nt-panel st-form">
      <h3 class="st-h3 st-full">检索</h3>
      <div class="st-row st-full">
        <div class="nt-field">
          <label class="nt-label" for="st-topk">TopK <small>· 每次最多带几段资料</small></label>
          <input id="st-topk" v-model.number="advanced.retrieval.topK" class="nt-input" type="number" min="1" max="10" step="1" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="st-sim">相似度门槛 <small>· 0 ~ 1</small></label>
          <input id="st-sim" v-model.number="advanced.retrieval.similarityThreshold" class="nt-input" type="number" min="0" max="1" step="0.05" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="st-history">历史窗口 <small>· 带上最近几条对话</small></label>
          <input id="st-history" v-model.number="advanced.retrieval.historyWindow" class="nt-input" type="number" min="2" max="20" step="1" />
        </div>
      </div>

      <h3 class="st-h3 st-full">回答</h3>
      <div class="st-row st-full">
        <div class="nt-field">
          <label class="nt-label" for="st-temp">温度 <small>· 越高越发散，0 ~ 1.5</small></label>
          <input id="st-temp" v-model.number="advanced.base.temperature" class="nt-input" type="number" min="0" max="1.5" step="0.05" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="st-max">推荐问题数 <small>· 1 ~ 6</small></label>
          <input id="st-max" v-model.number="advanced.base.maxSuggestions" class="nt-input" type="number" min="1" max="6" step="1" />
        </div>
        <label class="nt-switch st-switch-field">
          <input v-model="advanced.search.webSearchEnabled" type="checkbox" />
          <span class="nt-switch__track"></span>
          <span>联网搜索兜底 <small class="st-hint">站内什么都没查到时用火山联网搜索</small></span>
        </label>
      </div>

      <h3 class="st-h3 st-full">火山方舟</h3>
      <p class="st-hint st-full">向量检索要服务器开了 pgvector 才用；没开时按关键词检索，这里的 Embedding Key 用不上。Key 显示的是打码值，不改就保持原样。</p>
      <div class="st-row st-full">
        <div class="nt-field">
          <span class="nt-label">Base URL</span>
          <code class="st-readonly">{{ advanced.volcengine.baseUrl || '—' }}</code>
        </div>
        <div class="nt-field">
          <span class="nt-label">Embedding 模型</span>
          <code class="st-readonly">{{ advanced.volcengine.embeddingModel || '—' }}</code>
        </div>
        <div class="nt-field">
          <span class="nt-label">联网搜索模型</span>
          <code class="st-readonly">{{ advanced.volcengine.searchModel || '—' }}</code>
        </div>
      </div>
      <div class="st-row st-row--2 st-full">
        <div class="nt-field">
          <label class="nt-label" for="st-ekey">Embedding Key</label>
          <input id="st-ekey" v-model.trim="advanced.volcengine.embeddingApiKey" class="nt-input st-mono" type="password" autocomplete="off" placeholder="没填" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="st-skey">联网搜索 Key</label>
          <input id="st-skey" v-model.trim="advanced.volcengine.searchApiKey" class="nt-input st-mono" type="password" autocomplete="off" placeholder="没填" />
        </div>
      </div>

      <div class="st-save st-full">
        <span v-if="advancedChanges" class="st-changes">改了 {{ advancedChanges }} 处没保存</span>
        <button v-if="advancedChanges" type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="discard('advanced')">放弃改动</button>
        <button type="button" class="nt-btn nt-btn--ghost" @click="go(aiPath('qa'))">去试答几题</button>
        <button type="button" class="nt-btn nt-btn--gold" :disabled="!advancedChanges || saving === 'advanced'" @click="save('advanced')">
          {{ saving === 'advanced' ? '保存中…' : '保存高级设置' }}
        </button>
      </div>
    </section>
  </div>
  </div>
</template>

<style scoped>
/* 按这一块自己的宽度排版 */
.st-wrap {
  container-type: inline-size;
}

.st {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.st-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: sticky;
  top: 24px;
}

.st-nav__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 14px;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: var(--text-2);
  font: 700 15px var(--sans);
  text-align: left;
  cursor: pointer;
}

.st-nav__item small {
  color: var(--text-3);
  font-weight: 400;
  font-size: 12px;
}

.st-nav__item:hover {
  color: var(--text);
}

.st-nav__item.is-on {
  background: var(--panel-raised);
  color: var(--text);
  box-shadow: inset 3px 0 0 var(--gold);
}

.st-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-left: 4px;
  border-radius: 50%;
  background: var(--gold);
  vertical-align: middle;
}

.st-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.st-full {
  grid-column: 1 / -1;
}

.st-enable span {
  display: flex;
  flex-direction: column;
}

.st-hint {
  margin: 0;
  color: var(--text-3);
  font-size: 13px;
  font-weight: 400;
}

.st-tall {
  min-height: 120px;
}

.st-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--bg);
}

.st-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 4px 3px 11px;
  border-radius: 999px;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 13px;
}

.st-chip button {
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

.st-chip button:hover {
  background: var(--danger-bg);
  color: var(--camp-wolf);
}

.st-chip-input {
  flex: 1;
  min-width: 140px;
  height: 30px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font: 14px var(--sans);
}

.st-chip-input::placeholder {
  color: var(--moon);
}

.st-h3 {
  margin: 0;
  font: 900 16px var(--serif);
}

.st-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  align-items: end;
}

.st-row--2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.st-switch-field {
  min-height: 44px;
}

.st-switch-field span:last-child {
  display: flex;
  flex-direction: column;
}

.st-readonly {
  display: block;
  padding: 11px 14px;
  border-radius: 12px;
  background: var(--panel-raised);
  color: var(--text-2);
  font: 13px ui-monospace, monospace;
  overflow-wrap: anywhere;
}

.st-mono {
  font-family: ui-monospace, monospace;
}

.st-save {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}

.st-changes {
  color: var(--camp-god);
  font-size: 13px;
  margin-right: auto;
}

@container (max-width: 760px) {
  .st {
    grid-template-columns: 1fr;
  }

  .st-nav {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
  }

  .st-form,
  .st-row,
  .st-row--2 {
    grid-template-columns: 1fr;
  }
}
</style>
