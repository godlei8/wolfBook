<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImageField from '../ui/ImageField.vue'
import { api } from '../../services/api'
import type { Board, Role } from '../../types'
import { CAMP_META, campOf } from '../../utils/board'
import { resolveMediaUrl } from '../../utils/media'
import { cropPortrait } from '../../utils/portrait'

// 角色表单：图鉴页右侧编辑已有角色、「新建角色」弹窗里都用它
const props = withDefaults(
  defineProps<{
    role: Role | null
    boards: Board[]
    layout?: 'panel' | 'dialog'
  }>(),
  { layout: 'panel' },
)
const emit = defineEmits<{ saved: [role: Role]; removed: []; close: [] }>()

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

const saving = ref(false)
const makingPortrait = ref(false)

const form = reactive({
  id: props.role?.id || 0,
  name: props.role?.name || '',
  alias: props.role?.alias || '',
  faction: props.role?.faction || '好人',
  roleType: props.role?.roleType || '神职',
  skill: props.role?.skill || '',
  background: props.role?.background || '',
  portrait: props.role?.portrait || '',
  fullIllustration: props.role?.fullIllustration || '',
  themeColor: props.role?.themeColor || '',
  faqs: (props.role?.faqs || []).map((faq) => ({ ...faq })),
})

const isNew = computed(() => !form.id)
const typeOptions = computed(() => ROLE_TYPES[form.faction] || [])
const themeValid = computed(() => !form.themeColor || /^#[0-9a-fA-F]{6}$/.test(form.themeColor))
const previewAccent = computed(() =>
  themeValid.value && form.themeColor ? form.themeColor : CAMP_META[campOf({ faction: form.faction, roleType: form.roleType })].color,
)
const usedIn = computed(() => (form.id ? props.boards.filter((board) => board.roles.some((item) => item.roleId === form.id)) : []))

function setFaction(faction: string) {
  form.faction = faction
  if (!typeOptions.value.some((option) => option.value === form.roleType)) {
    form.roleType = typeOptions.value[0]?.value || ''
  }
}
setFaction(form.faction)

/** 上传了新立绘：按立绘裁一张方形头像并上传，不用再单独传头像。 */
async function onIllustrationUploaded(file: File) {
  makingPortrait.value = true
  try {
    const portrait = await cropPortrait(file)
    const result = await api.upload(portrait)
    form.portrait = result.url
    ElMessage.success('头像已按立绘自动裁好，不满意可以点「换头像」')
  } catch (error) {
    ElMessage.warning(error instanceof Error ? `${error.message}，请手动上传头像` : '头像自动生成失败，请手动上传')
  } finally {
    makingPortrait.value = false
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
  if (makingPortrait.value) {
    ElMessage.info('头像还在生成，稍等一下')
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
    emit('saved', saved)
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
    emit('removed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色删除失败')
  }
}
</script>

<template>
  <div class="editor" :class="`editor--${layout}`">
    <div v-if="layout === 'panel'" class="nt-row-between editor__top">
      <h2 class="editor__title">{{ form.name || '未命名角色' }}</h2>
      <button class="nt-iconbtn" type="button" aria-label="关闭编辑" @click="emit('close')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
      </button>
    </div>

    <div class="editor__art">
      <ImageField v-model="form.fullIllustration" class="editor__illustration" name="fullIllustration" label="立绘" hint="3:4 竖版，上传后自动裁头像" aspect="3 / 4" @uploaded="onIllustrationUploaded" />
      <div class="editor__side">
        <ImageField v-model="form.portrait" name="portrait" label="头像" hint="方形" aspect="1 / 1" />
        <div class="token-preview">
          <span class="nt-token" :style="{ width: '56px', height: '56px', boxShadow: `0 0 0 3px ${previewAccent}` }">
            <img v-if="form.portrait || form.fullIllustration" :src="resolveMediaUrl(form.portrait || form.fullIllustration)" alt="" />
            <span v-else>{{ form.name.slice(0, 1) || '?' }}</span>
          </span>
          <small>{{ makingPortrait ? '正在按立绘裁头像…' : '阵容里的样子' }}</small>
        </div>
      </div>
    </div>

    <div class="editor__fields">
      <div class="two">
        <div class="nt-field">
          <label class="nt-label" :for="`r-name-${layout}`">名称</label>
          <input :id="`r-name-${layout}`" v-model="form.name" class="nt-input" maxlength="30" placeholder="比如：狼美人" />
        </div>
        <div class="nt-field">
          <label class="nt-label" :for="`r-alias-${layout}`">别名</label>
          <input :id="`r-alias-${layout}`" v-model="form.alias" class="nt-input" placeholder="可不填" />
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
        <label class="nt-label" :for="`r-color-${layout}`">主题色 <small>· 图鉴卡底条、阵容头像描边，不填按阵营色</small></label>
        <div class="colors">
          <span class="colors__swatch" :style="{ background: previewAccent }"></span>
          <input :id="`r-color-${layout}`" v-model.trim="form.themeColor" class="nt-input colors__hex" :class="{ invalid: !themeValid }" placeholder="#RRGGBB" maxlength="7" />
          <button v-for="color in COLOR_PRESETS" :key="color" type="button" class="colors__preset" :class="{ on: form.themeColor.toUpperCase() === color }" :style="{ background: color }" :aria-label="`用 ${color}`" @click="form.themeColor = color"></button>
          <button type="button" class="colors__preset colors__preset--clear" aria-label="清空主题色，按阵营色" title="清空，按阵营色" @click="form.themeColor = ''"></button>
        </div>
      </div>

      <div class="nt-field">
        <label class="nt-label" :for="`r-skill-${layout}`">技能</label>
        <textarea :id="`r-skill-${layout}`" v-model="form.skill" class="nt-input" rows="4"></textarea>
      </div>
      <div class="nt-field">
        <label class="nt-label" :for="`r-bg-${layout}`">背景故事 <small>· 图鉴详情页展示</small></label>
        <textarea :id="`r-bg-${layout}`" v-model="form.background" class="nt-input" rows="4"></textarea>
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

      <p v-if="!isNew" class="used">
        出现在：
        <template v-if="usedIn.length">
          <a v-for="board in usedIn" :key="board.id" :href="`#/boards/${board.id}`">{{ board.name }}</a>
        </template>
        <span v-else>还没有板子用它</span>
      </p>
    </div>

    <div class="editor__actions">
      <button v-if="!isNew" class="nt-btn nt-btn--ghost" type="button" @click="remove">删除</button>
      <button v-else class="nt-btn nt-btn--ghost" type="button" @click="emit('close')">取消</button>
      <button class="nt-btn nt-btn--gold editor__save" type="button" :disabled="saving || makingPortrait" @click="save">
        {{ saving ? '保存中…' : makingPortrait ? '头像生成中…' : isNew ? '创建角色' : '保存角色' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.editor__title {
  margin: 0;
  font: 900 24px var(--serif);
}

.editor__art {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.editor__illustration {
  flex: 1 1 0;
}

.editor__side {
  flex: 0 0 132px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.editor__fields {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

/* 弹窗里横向排：左边图，右边表单 */
.editor--dialog {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 24px;
}

.editor--dialog .editor__art {
  flex-direction: column;
}

.editor--dialog .editor__illustration,
.editor--dialog .editor__side {
  flex: none;
  width: 100%;
}

.editor--dialog .editor__side {
  display: grid;
  grid-template-columns: 132px 1fr;
  align-items: end;
}

.editor--dialog .editor__actions {
  grid-column: 1 / -1;
  justify-content: flex-end;
}

.editor--dialog .editor__save {
  flex: 0 0 200px;
}

@media (max-width: 760px) {
  .editor--dialog {
    grid-template-columns: 1fr;
  }
}

.token-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-3);
  text-align: center;
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
