<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from './ui/PageHeader.vue'
import ImageField from './ui/ImageField.vue'
import { api } from '../services/api'
import type { Board, Role } from '../types'
import { CAMP_META, CAMP_ORDER, type CampKey, campOf, roleAccent } from '../utils/board'
import { resolveMediaUrl } from '../utils/media'

const emit = defineEmits<{ changed: [] }>()

const FACTIONS = ['好人', '狼人', '第三方']
// 值和小程序、后端一致，标签换成更好懂的说法
const ROLE_TYPES: Record<string, { value: string; label: string }[]> = {
  好人: [
    { value: '神职', label: '神职' },
    { value: '平民', label: '平民' },
  ],
  狼人: [
    { value: '狼人', label: '普通狼' },
    { value: '功能狼', label: '特殊狼' },
  ],
  第三方: [{ value: '第三方', label: '第三方' }],
}
/** 和美术提示词里各角色的专属色一致，方便直接点选。 */
const COLOR_PRESETS = ['#7B4FD6', '#F2B33D', '#E8772E', '#4A78A8', '#B3263A', '#C0262D', '#D63A7A', '#2FB8A6', '#2F5D46', '#7FC8E8', '#7A1A22', '#9A6A3A', '#2A8FB8', '#3F3DA8', '#F28CB8', '#D9902A']

const loading = ref(false)
const saving = ref(false)
const roles = ref<Role[]>([])
const boards = ref<Board[]>([])
const campFilter = ref<CampKey | 'ALL'>('ALL')
const onlyMissingArt = ref(false)
const keyword = ref('')
const editing = ref(false)

const form = reactive({
  id: 0,
  name: '',
  alias: '',
  faction: '好人',
  roleType: '神职',
  skill: '',
  background: '',
  portrait: '',
  fullIllustration: '',
  themeColor: '',
  faqs: [] as { question: string; answer: string }[],
})

const counts = computed(() => {
  const result: Record<CampKey, number> = { god: 0, villager: 0, wolf: 0, third: 0 }
  roles.value.forEach((role) => {
    result[campOf(role)] += 1
  })
  return result
})
const missingArt = computed(() => roles.value.filter((role) => !role.fullIllustration).length)

const filtered = computed(() => {
  const text = keyword.value.trim()
  return roles.value.filter((role) => {
    if (campFilter.value !== 'ALL' && campOf(role) !== campFilter.value) return false
    if (onlyMissingArt.value && role.fullIllustration) return false
    if (text && !`${role.name}${role.alias || ''}`.includes(text)) return false
    return true
  })
})

const typeOptions = computed(() => ROLE_TYPES[form.faction] || [])
const themeValid = computed(() => !form.themeColor || /^#[0-9a-fA-F]{6}$/.test(form.themeColor))
const previewAccent = computed(() =>
  themeValid.value && form.themeColor ? form.themeColor : CAMP_META[campOf({ faction: form.faction, roleType: form.roleType })].color,
)
const usedIn = computed(() => (form.id ? boards.value.filter((board) => board.roles.some((item) => item.roleId === form.id)) : []))

function cardImage(role: Role) {
  return resolveMediaUrl(role.fullIllustration)
}

function setFaction(faction: string) {
  form.faction = faction
  if (!typeOptions.value.some((option) => option.value === form.roleType)) {
    form.roleType = typeOptions.value[0]?.value || ''
  }
}

function open(role?: Role) {
  form.id = role?.id || 0
  form.name = role?.name || ''
  form.alias = role?.alias || ''
  form.faction = role?.faction || '好人'
  form.roleType = role?.roleType || '神职'
  form.skill = role?.skill || ''
  form.background = role?.background || ''
  form.portrait = role?.portrait || ''
  form.fullIllustration = role?.fullIllustration || ''
  form.themeColor = role?.themeColor || ''
  form.faqs = (role?.faqs || []).map((faq) => ({ ...faq }))
  setFaction(form.faction)
  editing.value = true
}

async function load() {
  loading.value = true
  try {
    const [roleData, boardData] = await Promise.all([api.getRoles(), api.getBoards()])
    roles.value = roleData
    boards.value = boardData
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('角色名称没填')
    return
  }
  if (!themeValid.value) {
    ElMessage.warning('主题色要写成 #RRGGBB，比如 #D63A7A')
    return
  }
  const payload = {
    name: form.name.trim(),
    alias: form.alias.trim(),
    faction: form.faction,
    roleType: form.roleType,
    skill: form.skill,
    background: form.background,
    faqs: form.faqs.filter((faq) => faq.question.trim() && faq.answer.trim()),
    portrait: form.portrait,
    fullIllustration: form.fullIllustration,
    themeColor: form.themeColor || null,
  }
  saving.value = true
  try {
    const saved = form.id ? await api.updateRole(form.id, payload) : await api.createRole(payload)
    ElMessage.success(form.id ? '角色已保存' : '角色已创建')
    await load()
    open(roles.value.find((role) => role.id === saved.id) || saved)
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色保存失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (usedIn.value.length) {
    ElMessage.warning(`「${form.name}」还在 ${usedIn.value.map((board) => board.name).join('、')} 里，先从这些板子的阵容里去掉`)
    return
  }
  try {
    await ElMessageBox.confirm(`删除「${form.name}」，不能恢复。`, '删除角色', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api.deleteRole(form.id)
    ElMessage.success('角色已删除')
    editing.value = false
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色删除失败')
  }
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="ROLES" title="角色图鉴">
    <template #description>
      {{ roles.length }} 个角色，{{ roles.length - missingArt }} 个有立绘。立绘、头像和主题色会用在图鉴、板子阵容和法官房。
    </template>
    <template #actions>
      <button class="nt-btn nt-btn--gold" type="button" @click="open()">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
        新建角色
      </button>
    </template>
  </PageHeader>

  <section class="filters" aria-label="筛选">
    <button type="button" class="nt-chip" :class="{ 'nt-chip--on': campFilter === 'ALL' }" @click="campFilter = 'ALL'">全部 {{ roles.length }}</button>
    <button v-for="camp in CAMP_ORDER" :key="camp" type="button" class="nt-chip" :class="{ 'nt-chip--on': campFilter === camp }" @click="campFilter = camp">
      <span class="nt-dot" :style="{ background: CAMP_META[camp].color }"></span>{{ CAMP_META[camp].label }} {{ counts[camp] }}
    </button>
    <span class="nt-divider"></span>
    <button type="button" class="nt-chip" :class="{ 'nt-chip--on': onlyMissingArt }" :aria-pressed="onlyMissingArt" @click="onlyMissingArt = !onlyMissingArt">只看缺立绘 {{ missingArt }}</button>
    <label class="search">
      <span class="nt-sr-only">搜索角色</span>
      <input v-model="keyword" type="search" class="nt-input" placeholder="搜角色名" />
    </label>
  </section>

  <div class="layout">
    <section class="grid" aria-label="角色">
      <p v-if="loading && !roles.length" class="nt-empty">正在翻图鉴…</p>
      <p v-else-if="!filtered.length" class="nt-empty">没有符合条件的角色</p>
      <button
        v-for="role in filtered"
        :key="role.id"
        type="button"
        class="rc"
        :class="{ 'rc--on': editing && form.id === role.id }"
        :aria-pressed="editing && form.id === role.id"
        @click="open(role)"
      >
        <span class="rc__art" :class="{ 'nt-cardback': !cardImage(role) }">
          <img v-if="cardImage(role)" :src="cardImage(role)" alt="" loading="lazy" />
          <span v-else class="rc__missing">
            <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></svg>
            待上传立绘
          </span>
        </span>
        <span class="rc__foot" :style="{ borderTopColor: roleAccent(role) }">
          <strong>{{ role.name }}</strong>
          <small :style="{ color: CAMP_META[campOf(role)].color }">{{ CAMP_META[campOf(role)].label }}</small>
        </span>
      </button>
    </section>

    <aside v-if="editing" class="editor" :aria-label="form.id ? `编辑${form.name}` : '新建角色'">
      <div class="nt-row-between">
        <h2 class="editor__title">{{ form.id ? form.name || '未命名角色' : '新建角色' }}</h2>
        <button class="nt-iconbtn" type="button" aria-label="关闭编辑" @click="editing = false">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
        </button>
      </div>

      <div class="art-row">
        <ImageField v-model="form.fullIllustration" class="art-row__main" name="fullIllustration" label="立绘" hint="3:4 竖版" aspect="3 / 4" />
        <div class="art-row__side">
          <ImageField v-model="form.portrait" name="portrait" label="头像" hint="方形" aspect="1 / 1" />
          <div class="token-preview">
            <span class="nt-token" :style="{ width: '56px', height: '56px', boxShadow: `0 0 0 3px ${previewAccent}` }">
              <img v-if="form.portrait || form.fullIllustration" :src="resolveMediaUrl(form.portrait || form.fullIllustration)" alt="" />
              <span v-else>{{ form.name.slice(0, 1) || '?' }}</span>
            </span>
            <small>阵容里的样子</small>
          </div>
        </div>
      </div>

      <div class="two">
        <div class="nt-field">
          <label class="nt-label" for="r-name">名称</label>
          <input id="r-name" v-model="form.name" class="nt-input" maxlength="30" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="r-alias">别名</label>
          <input id="r-alias" v-model="form.alias" class="nt-input" placeholder="可不填" />
        </div>
      </div>

      <div class="nt-field">
        <span class="nt-label">阵营</span>
        <div class="nt-seg" role="radiogroup" aria-label="阵营">
          <button v-for="faction in FACTIONS" :key="faction" type="button" role="radio" :aria-checked="form.faction === faction" :class="{ on: form.faction === faction }" @click="setFaction(faction)">{{ faction }}</button>
        </div>
      </div>
      <div class="nt-field">
        <span class="nt-label">类型</span>
        <div class="nt-seg" role="radiogroup" aria-label="类型">
          <button v-for="option in typeOptions" :key="option.value" type="button" role="radio" :aria-checked="form.roleType === option.value" :class="{ on: form.roleType === option.value }" @click="form.roleType = option.value">{{ option.label }}</button>
        </div>
      </div>

      <div class="nt-field">
        <label class="nt-label" for="r-color">主题色 <small>· 图鉴卡底条、阵容头像描边，不填按阵营色</small></label>
        <div class="colors">
          <span class="colors__swatch" :style="{ background: previewAccent }"></span>
          <input id="r-color" v-model.trim="form.themeColor" class="nt-input colors__hex" :class="{ invalid: !themeValid }" placeholder="#RRGGBB" maxlength="7" />
          <button v-for="color in COLOR_PRESETS" :key="color" type="button" class="colors__preset" :class="{ on: form.themeColor.toUpperCase() === color }" :style="{ background: color }" :aria-label="`用 ${color}`" @click="form.themeColor = color"></button>
          <button type="button" class="colors__preset colors__preset--clear" aria-label="清空主题色，按阵营色" title="清空，按阵营色" @click="form.themeColor = ''"></button>
        </div>
      </div>

      <div class="nt-field">
        <label class="nt-label" for="r-skill">技能</label>
        <textarea id="r-skill" v-model="form.skill" class="nt-input" rows="4"></textarea>
      </div>
      <div class="nt-field">
        <label class="nt-label" for="r-bg">背景故事 <small>· 图鉴详情页展示</small></label>
        <textarea id="r-bg" v-model="form.background" class="nt-input" rows="4"></textarea>
      </div>

      <div class="nt-field">
        <span class="nt-label">常见问题 <small>· {{ form.faqs.length }} 组</small></span>
        <div v-for="(faq, index) in form.faqs" :key="index" class="faq">
          <input v-model="faq.question" class="nt-input" :aria-label="`第 ${index + 1} 个问题`" placeholder="问题" />
          <textarea v-model="faq.answer" class="nt-input" rows="2" :aria-label="`第 ${index + 1} 个回答`" placeholder="回答"></textarea>
          <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="form.faqs.splice(index, 1)">删除这组</button>
        </div>
        <button type="button" class="nt-btn nt-btn--ghost nt-btn--small faq-add" @click="form.faqs.push({ question: '', answer: '' })">+ 加一组问答</button>
      </div>

      <p v-if="form.id" class="used">
        出现在：
        <template v-if="usedIn.length">
          <a v-for="board in usedIn" :key="board.id" :href="`#/boards/${board.id}`">{{ board.name }}</a>
        </template>
        <span v-else>还没有板子用它</span>
      </p>

      <div class="editor__actions">
        <button v-if="form.id" class="nt-btn nt-btn--ghost" type="button" @click="remove">删除</button>
        <button class="nt-btn nt-btn--gold editor__save" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : form.id ? '保存角色' : '创建角色' }}</button>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.search {
  margin-left: auto;
  width: 200px;
}

.search .nt-input {
  height: 36px;
  border-radius: 999px;
}

.layout {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  align-items: flex-start;
}

.grid {
  flex: 999 1 480px;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(138px, 1fr));
  gap: 18px;
}

.grid .nt-empty {
  grid-column: 1 / -1;
  margin: 0;
}

.rc {
  padding: 0;
  border-radius: 18px;
  border: 2px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
  background: var(--panel);
  color: var(--text);
  text-align: left;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  cursor: pointer;
  transition: transform 0.1s ease;
}

.rc:hover {
  transform: translateY(-2px);
}

.rc--on {
  border: 3px solid var(--moon);
  box-shadow: 5px 5px 0 var(--ink);
}

.rc__art {
  position: relative;
  aspect-ratio: 3 / 4;
  overflow: hidden;
}

.rc__art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 50% 10%;
}

.rc__missing {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
  font-size: 12px;
}

.rc__foot {
  padding: 10px 12px;
  border-top: 4px solid var(--line);
  display: flex;
  align-items: center;
  gap: 8px;
}

.rc__foot strong {
  font: 900 16px var(--serif);
}

.rc__foot small {
  margin-left: auto;
  font-size: 12px;
}

.editor {
  flex: 1 0 360px;
  max-width: 100%;
  padding: 24px;
  border-radius: 22px;
  background: var(--panel);
  border: 2px solid var(--ink);
  box-shadow: 5px 5px 0 var(--ink);
  display: flex;
  flex-direction: column;
  gap: 18px;
}

@media (min-width: 1200px) {
  .editor {
    position: sticky;
    top: 0;
    max-height: calc(100vh - 64px);
    overflow-y: auto;
  }
}

.editor__title {
  margin: 0;
  font: 900 24px var(--serif);
}

.art-row {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.art-row__main {
  flex: 1 1 0;
}

.art-row__side {
  flex: 0 0 132px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.token-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-3);
}

.two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.colors {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.colors__swatch {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  border: 2px solid var(--ink);
  box-shadow: 3px 3px 0 var(--ink);
}

.colors__hex {
  width: 116px;
  font-family: ui-monospace, monospace;
}

.colors__hex.invalid {
  border-color: var(--camp-wolf);
}

.colors__preset {
  width: 26px;
  height: 26px;
  padding: 0;
  border-radius: 8px;
  border: 2px solid var(--ink);
  cursor: pointer;
}

.colors__preset.on {
  outline: 2px solid var(--text);
  outline-offset: 1px;
}

.colors__preset--clear {
  background: transparent;
  border-style: dashed;
  border-color: var(--text-3);
}

.faq {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border-radius: 14px;
  background: var(--panel-raised);
}

.faq .nt-btn {
  align-self: flex-end;
}

.faq-add {
  align-self: flex-start;
}

.used {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: var(--text-3);
}

.used a {
  text-decoration: none;
}

.editor__actions {
  display: flex;
  gap: 10px;
}

.editor__save {
  flex: 1;
}
</style>
