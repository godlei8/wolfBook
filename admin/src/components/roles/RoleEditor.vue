<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImageField from '../ui/ImageField.vue'
import RoleToken from '../ui/RoleToken.vue'
import { api } from '../../services/api'
import type { Board, Role, WorldEvent } from '../../types'
import { CAMP_META, campOf } from '../../utils/board'
import { resolveMediaUrl } from '../../utils/media'
import { cropPortrait } from '../../utils/portrait'
import { checkRole, eventRoleIds } from '../../utils/roleCompleteness'

// 角色表单：新建和编辑都在图鉴页的弹窗里用它。左边立绘 + 头像，右边文字设定
const props = withDefaults(
  defineProps<{
    role: Role | null
    boards: Board[]
    /** 全部角色，人物关系从这里选 */
    roles: Role[]
    /** 编年史事件，用来判断「在编年史里出场」 */
    worldEvents?: WorldEvent[]
    /** 双向关系提示打开时，预先加一行指向这个角色的关系 */
    prefillRelationTo?: number | null
  }>(),
  { worldEvents: () => [], prefillRelationTo: null },
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
const MAX_RELATIONS = 8
const COLOR_PRESETS = ['#7B4FD6', '#F2B33D', '#E8772E', '#4A78A8', '#B3263A', '#C0262D', '#D63A7A', '#2FB8A6', '#2F5D46', '#7FC8E8', '#7A1A22', '#9A6A3A', '#2A8FB8', '#3F3DA8', '#F28CB8', '#D9902A']

const saving = ref(false)
const drafting = ref(false)
const draftNotes = ref('')
const root = ref<HTMLElement | null>(null)
const makingPortrait = ref(false)
const uploadingPortrait = ref(false)
const portraitInput = ref<HTMLInputElement | null>(null)
// 这次弹窗里上传过的立绘文件：COS 不给跨域读图，只有它能在浏览器里重新裁头像
const illustrationFile = ref<File | null>(null)

const form = reactive({
  id: props.role?.id || 0,
  name: props.role?.name || '',
  alias: props.role?.alias || '',
  title: props.role?.title || '',
  relations: (props.role?.relations || []).map((relation) => ({ ...relation })),
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
const rolesById = computed(() => new Map(props.roles.map((role) => [role.id, role])))
// 设定完整度跟着表单实时算，规则在 utils/roleCompleteness.ts
const castIds = computed(() => eventRoleIds(props.worldEvents))
const check = computed(() => checkRole(form, rolesById.value, castIds.value))

if (props.prefillRelationTo && props.prefillRelationTo !== form.id && !form.relations.some((relation) => relation.roleId === props.prefillRelationTo)) {
  form.relations.push({ roleId: props.prefillRelationTo, label: '' })
}

onMounted(async () => {
  if (!props.prefillRelationTo) return
  await nextTick()
  // 弹窗打开动画结束时 Element Plus 会把焦点拉回弹窗本身，等它之后再聚焦到新加的那一行
  window.setTimeout(() => {
    const inputs = root.value?.querySelectorAll<HTMLInputElement>('.relation input')
    const last = inputs?.[inputs.length - 1]
    last?.scrollIntoView({ block: 'center' })
    last?.focus()
  }, 350)
})

/** 按写作规范、世界观和现有角色，让 AI 起草真名、称号、身世和关系，填进表单，不直接保存。 */
async function draft() {
  if (!form.name.trim() || !form.skill.trim()) {
    ElMessage.warning('先填角色名和技能，AI 才知道要写谁')
    return
  }
  if (form.alias.trim() || form.title.trim() || form.background.trim() || form.relations.length) {
    try {
      await ElMessageBox.confirm('起草会替换现在的真名、称号、身世和人物关系。', '按世界观起草', {
        confirmButtonText: '替换',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  drafting.value = true
  try {
    const result = await api.draftRole({
      roleId: form.id || null,
      name: form.name.trim(),
      faction: form.faction,
      roleType: form.roleType,
      skill: form.skill.trim(),
      notes: draftNotes.value.trim(),
    })
    form.alias = result.alias || ''
    form.title = result.title || ''
    form.background = result.background || ''
    form.relations = (result.relations || []).map((relation) => ({ ...relation }))
    ElMessage.success('草稿已填好，改完再保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 起草失败')
  } finally {
    drafting.value = false
  }
}
/** 关系可选的角色：除了自己，也排除已经写过的（当前这一行自己的选择保留）。 */
function relationOptions(current: number) {
  const used = new Set(form.relations.map((relation) => relation.roleId))
  return props.roles.filter((role) => role.id !== form.id && (role.id === current || !used.has(role.id)))
}

function addRelation() {
  const next = relationOptions(0)[0]
  form.relations.push({ roleId: next?.id ?? 0, label: '' })
}

const portraitBusy = computed(() => makingPortrait.value || uploadingPortrait.value)
const portraitHint = computed(() => {
  if (makingPortrait.value) return '正在按立绘裁头像…'
  if (uploadingPortrait.value) return '头像上传中…'
  if (form.portrait) return '阵容、图鉴、法官房里显示的圆头像'
  if (form.fullIllustration) return '还没有头像，暂时用立绘顶上'
  return '上传立绘后会自动裁好'
})

function setFaction(faction: string) {
  form.faction = faction
  if (!typeOptions.value.some((option) => option.value === form.roleType)) {
    form.roleType = typeOptions.value[0]?.value || ''
  }
}
setFaction(form.faction)

async function makePortraitFrom(file: File) {
  makingPortrait.value = true
  try {
    const result = await api.upload(await cropPortrait(file))
    form.portrait = result.url
    ElMessage.success('头像已按立绘自动裁好')
  } catch (error) {
    ElMessage.warning(error instanceof Error ? `${error.message}，请手动上传头像` : '头像自动生成失败，请手动上传')
  } finally {
    makingPortrait.value = false
  }
}

/** 上传了新立绘：按立绘裁一张方形头像并上传，不用再单独传头像。 */
function onIllustrationUploaded(file: File) {
  illustrationFile.value = file
  void makePortraitFrom(file)
}

async function uploadPortrait(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/^image\/(jpeg|png|gif|webp)$/.test(file.type) || file.size > 5 * 1024 * 1024) {
    ElMessage.error('头像只能是 5MB 以内的 jpg / png / gif / webp')
    return
  }
  uploadingPortrait.value = true
  try {
    form.portrait = (await api.upload(file)).url
    ElMessage.success('头像已上传')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '头像上传失败')
  } finally {
    uploadingPortrait.value = false
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
  if (portraitBusy.value) {
    ElMessage.info('头像还在处理，稍等一下')
    return
  }
  const relations = form.relations.filter((relation) => relation.roleId && relation.label.trim())
  if (relations.length !== form.relations.length) {
    ElMessage.warning('人物关系要选角色并写一句关系，空的那一行先删掉')
    return
  }
  const payload = {
    name: form.name.trim(),
    alias: form.alias.trim(),
    title: form.title.trim(),
    relations: relations.map((relation) => ({ roleId: relation.roleId, label: relation.label.trim() })),
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
  <div ref="root" class="editor">
    <!-- 左：立绘 + 头像 -->
    <section class="art" aria-label="图片">
      <ImageField v-model="form.fullIllustration" name="fullIllustration" label="立绘" hint="3:4 竖版" aspect="3 / 4" @uploaded="onIllustrationUploaded" />

      <div class="portrait">
        <span class="nt-token portrait__token" :style="{ boxShadow: `0 0 0 3px ${previewAccent}` }">
          <img v-if="form.portrait || form.fullIllustration" :src="resolveMediaUrl(form.portrait || form.fullIllustration)" alt="头像预览" />
          <span v-else>{{ form.name.slice(0, 1) || '?' }}</span>
        </span>
        <div class="portrait__body">
          <strong>头像</strong>
          <small>{{ portraitHint }}</small>
          <div class="portrait__btns">
            <button v-if="illustrationFile" type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="portraitBusy" @click="makePortraitFrom(illustrationFile)">按立绘重裁</button>
            <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="portraitBusy" @click="portraitInput?.click()">{{ form.portrait ? '换头像' : '上传头像' }}</button>
            <button v-if="form.portrait" type="button" class="nt-iconbtn" aria-label="清空头像" title="清空头像" :disabled="portraitBusy" @click="form.portrait = ''">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
            </button>
          </div>
        </div>
        <input ref="portraitInput" class="nt-sr-only" type="file" accept="image/jpeg,image/png,image/gif,image/webp" aria-label="上传头像" @change="uploadPortrait" />
      </div>

      <div class="nt-field">
        <span class="nt-label">主题色 <small>· 不填按阵营色</small></span>
        <div class="colors">
          <span class="colors__swatch" :style="{ background: previewAccent }"></span>
          <input v-model.trim="form.themeColor" class="nt-input colors__hex" :class="{ invalid: !themeValid }" aria-label="主题色色值" placeholder="#RRGGBB" maxlength="7" />
        </div>
        <div class="colors__presets">
          <button v-for="color in COLOR_PRESETS" :key="color" type="button" class="colors__preset" :class="{ on: form.themeColor.toUpperCase() === color }" :style="{ background: color }" :aria-label="`用 ${color}`" @click="form.themeColor = color"></button>
          <button type="button" class="colors__preset colors__preset--clear" aria-label="清空主题色，按阵营色" title="清空，按阵营色" @click="form.themeColor = ''"></button>
        </div>
      </div>

      <div class="check" :class="{ 'check--done': check.complete }" aria-label="设定完整度">
        <div class="check__head">
          <strong>{{ check.complete ? '设定完整' : `设定还缺 ${check.missing.length} 项` }}</strong>
          <small>照写作规范检查</small>
        </div>
        <ul class="check__list">
          <li v-for="item in check.required" :key="item.key" :class="item.ok ? 'ok' : 'no'">
            <span class="check__mark" aria-hidden="true">{{ item.ok ? '✓' : '✗' }}</span>
            <span class="check__text">{{ item.label }}<small v-if="!item.ok">{{ item.hint }}</small></span>
          </li>
        </ul>
        <ul class="check__list check__list--tips">
          <li v-for="item in check.suggestions" :key="item.key" :class="item.ok ? 'ok' : 'tip'">
            <span class="check__mark" aria-hidden="true">{{ item.ok ? '✓' : '·' }}</span>
            <span class="check__text">建议：{{ item.label }}<small v-if="!item.ok">{{ item.hint }}</small></span>
          </li>
        </ul>
      </div>
    </section>

    <!-- 右：文字设定 -->
    <section class="fields" aria-label="设定">
      <div class="draft">
        <button type="button" class="nt-btn nt-btn--cream nt-btn--small" :disabled="drafting" @click="draft">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6" /></svg>
          {{ drafting ? 'AI 正在写…' : '按世界观起草' }}
        </button>
        <input v-model="draftNotes" class="nt-input draft__notes" maxlength="300" aria-label="给 AI 的补充要求" placeholder="补充要求（可不填），比如：和猎人是旧识、是个女孩" />
      </div>
      <div class="grid3">
        <div class="nt-field">
          <label class="nt-label" for="role-name">角色名</label>
          <input id="role-name" v-model="form.name" class="nt-input" maxlength="30" placeholder="比如：狼美人" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="role-alias">真名 <small>· 世界观里的人名</small></label>
          <input id="role-alias" v-model="form.alias" class="nt-input" maxlength="50" placeholder="比如：塞拉菲娜·罗丝" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="role-title">称号</label>
          <input id="role-title" v-model="form.title" class="nt-input" maxlength="40" placeholder="比如：玫瑰夫人" />
        </div>
      </div>
      <div class="grid2">
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
      </div>

      <div class="nt-field">
        <label class="nt-label" for="role-skill">技能</label>
        <textarea id="role-skill" v-model="form.skill" class="nt-input" rows="4" placeholder="夜里能做什么、白天有什么能力"></textarea>
      </div>
      <div class="nt-field">
        <label class="nt-label" for="role-bg">身世 <small>· 图鉴详情页展示，{{ form.background.length }} 字</small></label>
        <textarea id="role-bg" v-model="form.background" class="nt-input" rows="6" placeholder="这个人从哪来、经历过什么、为什么站在这一边"></textarea>
      </div>

      <div class="nt-field">
        <div class="nt-row-between">
          <span class="nt-label">人物关系 <small>· {{ form.relations.length }} / {{ MAX_RELATIONS }}，详情页点头像能跳过去</small></span>
          <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" :disabled="form.relations.length >= MAX_RELATIONS" @click="addRelation">+ 加一个关系</button>
        </div>
        <div v-for="(relation, index) in form.relations" :key="index" class="relation">
          <RoleToken :role="rolesById.get(relation.roleId)" :size="36" ring />
          <select v-model.number="relation.roleId" class="nt-input relation__role" :aria-label="`第 ${index + 1} 个关系的角色`">
            <option v-for="option in relationOptions(relation.roleId)" :key="option.id" :value="option.id">{{ option.name }}{{ option.alias ? ` · ${option.alias}` : '' }}</option>
          </select>
          <input v-model="relation.label" class="nt-input" maxlength="20" :aria-label="`第 ${index + 1} 个关系的说明`" placeholder="一句话关系，比如：心上人、宿敌" />
          <button type="button" class="nt-iconbtn" :aria-label="`删除第 ${index + 1} 个关系`" title="删除" @click="form.relations.splice(index, 1)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
        </div>
      </div>

      <div class="nt-field">
        <div class="nt-row-between">
          <span class="nt-label">常见问题 <small>· {{ form.faqs.length }} 组</small></span>
          <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="form.faqs.push({ question: '', answer: '' })">+ 加一组问答</button>
        </div>
        <div v-for="(faq, index) in form.faqs" :key="index" class="faq">
          <input v-model="faq.question" class="nt-input" :aria-label="`第 ${index + 1} 个问题`" placeholder="问题" />
          <textarea v-model="faq.answer" class="nt-input" rows="2" :aria-label="`第 ${index + 1} 个回答`" placeholder="回答"></textarea>
          <button type="button" class="nt-iconbtn" :aria-label="`删除第 ${index + 1} 组问答`" title="删除这组" @click="form.faqs.splice(index, 1)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
        </div>
      </div>
    </section>

    <footer class="actions">
      <p v-if="!isNew" class="used">
        出现在：
        <template v-if="usedIn.length">
          <a v-for="board in usedIn" :key="board.id" :href="`#/boards/${board.id}`">{{ board.name }}</a>
        </template>
        <span v-else>还没有板子用它</span>
      </p>
      <span v-else class="used">新角色保存后可以在板子的阵容里选到</span>
      <button v-if="!isNew" class="nt-btn nt-btn--ghost" type="button" @click="remove">删除</button>
      <button class="nt-btn nt-btn--ghost" type="button" @click="emit('close')">取消</button>
      <button class="nt-btn nt-btn--gold actions__save" type="button" :disabled="saving || portraitBusy" @click="save">
        {{ saving ? '保存中…' : portraitBusy ? '头像处理中…' : isNew ? '创建角色' : '保存角色' }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.editor {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 22px 28px;
}

.art,
.fields {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.portrait {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px;
  border-radius: 16px;
  background: var(--panel-raised);
}

.portrait__token {
  width: 72px;
  height: 72px;
  font-size: 26px;
}

.portrait__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.portrait__body strong {
  font-size: 14px;
}

.portrait__body small {
  font-size: 12px;
  color: var(--text-3);
}

.portrait__btns {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.portrait__btns .nt-btn {
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
}

.colors {
  display: flex;
  align-items: center;
  gap: 10px;
}

.colors__swatch {
  width: 44px;
  height: 44px;
  flex: none;
  border-radius: 12px;
  border: 2px solid var(--ink);
  box-shadow: 3px 3px 0 var(--ink);
}

.colors__hex {
  font-family: ui-monospace, monospace;
}

.colors__hex.invalid {
  border-color: var(--camp-wolf);
}

.colors__presets {
  display: grid;
  grid-template-columns: repeat(9, 26px);
  gap: 7px;
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

.check {
  padding: 14px 16px;
  border-radius: 16px;
  background: var(--panel-raised);
  border: 1px solid var(--warn-line);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.check--done {
  border-color: #2b5c52;
}

.check__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.check__head strong {
  font-size: 14px;
  color: var(--gold);
}

.check--done .check__head strong {
  color: var(--camp-villager);
}

.check__head small {
  font-size: 12px;
  color: var(--text-3);
}

.check__list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}

.check__list--tips {
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}

.check__list li {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.check__mark {
  width: 16px;
  flex: none;
  font-weight: 900;
  text-align: center;
}

.check__list .ok {
  color: var(--text-2);
}

.check__list .ok .check__mark {
  color: var(--camp-villager);
}

.check__list .no {
  color: var(--text);
}

.check__list .no .check__mark {
  color: var(--camp-wolf);
}

.check__list .tip {
  color: var(--text-3);
}

.check__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.check__text small {
  font-size: 12px;
  color: var(--text-3);
}

.draft {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px;
  border-radius: 14px;
  background: var(--panel-raised);
}

.draft .nt-btn {
  flex: none;
}

.draft__notes {
  height: 36px;
  font-size: 13px;
}

.grid2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.grid3 {
  display: grid;
  grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 16px;
}

.relation {
  display: grid;
  grid-template-columns: 40px 230px minmax(0, 1fr) 32px;
  gap: 10px;
  align-items: center;
}

.relation__role {
  height: 40px;
}

.faq {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.5fr) 32px;
  gap: 10px;
  align-items: start;
}

.actions {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding-top: 18px;
  border-top: 1px solid var(--panel-line);
}

.used {
  margin: 0 auto 0 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: var(--text-3);
}

.used a {
  text-decoration: none;
}

.actions__save {
  min-width: 160px;
}

@media (max-width: 760px) {
  .editor,
  .grid2,
  .grid3,
  .faq {
    grid-template-columns: 1fr;
  }
}
</style>
