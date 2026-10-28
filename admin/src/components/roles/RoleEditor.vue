<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImageField from '../ui/ImageField.vue'
import { api } from '../../services/api'
import type { Board, Role } from '../../types'
import { CAMP_META, campOf } from '../../utils/board'
import { resolveMediaUrl } from '../../utils/media'
import { cropPortrait } from '../../utils/portrait'

// 角色表单：新建和编辑都在图鉴页的弹窗里用它。左边立绘 + 头像，右边文字设定
const props = defineProps<{
  role: Role | null
  boards: Board[]
}>()
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
const uploadingPortrait = ref(false)
const portraitInput = ref<HTMLInputElement | null>(null)
// 这次弹窗里上传过的立绘文件：COS 不给跨域读图，只有它能在浏览器里重新裁头像
const illustrationFile = ref<File | null>(null)

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
  <div class="editor">
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
    </section>

    <!-- 右：文字设定 -->
    <section class="fields" aria-label="设定">
      <div class="grid2">
        <div class="nt-field">
          <label class="nt-label" for="role-name">名称</label>
          <input id="role-name" v-model="form.name" class="nt-input" maxlength="30" placeholder="比如：狼美人" />
        </div>
        <div class="nt-field">
          <label class="nt-label" for="role-alias">别名</label>
          <input id="role-alias" v-model="form.alias" class="nt-input" placeholder="可不填" />
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
      </div>

      <div class="nt-field">
        <label class="nt-label" for="role-skill">技能</label>
        <textarea id="role-skill" v-model="form.skill" class="nt-input" rows="4" placeholder="夜里能做什么、白天有什么能力"></textarea>
      </div>
      <div class="nt-field">
        <label class="nt-label" for="role-bg">背景故事 <small>· 图鉴详情页展示</small></label>
        <textarea id="role-bg" v-model="form.background" class="nt-input" rows="4"></textarea>
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

.grid2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
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
  .faq {
    grid-template-columns: 1fr;
  }
}
</style>
