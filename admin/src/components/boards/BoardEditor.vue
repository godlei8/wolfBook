<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ImageField from '../ui/ImageField.vue'
import RoleToken from '../ui/RoleToken.vue'
import CampBar from '../ui/CampBar.vue'
import BoardPreview from './BoardPreview.vue'
import { api } from '../../services/api'
import type { Board, BoardRuleDetail, NightStep, Role } from '../../types'
import {
  CAMP_META,
  CAMP_ORDER,
  type CampKey,
  campOf,
  campSegments,
  defaultNightOrder,
  featuredBoards,
  lineupSize,
  roleAccent,
  sortLineup,
  JUDGE_SUPPORT_META,
  judgeSupport,
  isWolfTeam,
} from '../../utils/board'

const props = defineProps<{ boardId: number | null }>()
const emit = defineEmits<{ saved: []; close: [] }>()

const loading = ref(true)
const saving = ref(false)
const statusBusy = ref(false)
const roles = ref<Role[]>([])
const allBoards = ref<Board[]>([])
const pickerCamp = ref<CampKey>('god')
const tagDraft = ref('')
const openExtra = reactive({ special: false, tips: false, faqs: false })
const dragIndex = ref<number | null>(null)
let snapshot = ''

interface RoleRuleDraft {
  roleId: number
  text: string
}

interface SectionDraft {
  title: string
  text: string
}

const form = reactive({
  id: 0,
  name: '',
  playerCount: 12,
  difficulty: '入门',
  tags: [] as string[],
  coverImage: '',
  backgroundImage: '',
  cardDescription: '',
  winCondition: '屠边',
  ruleType: '标准板',
  status: 1,
  roles: [] as { roleId: number; count: number }[],
  overview: '',
  nightOrder: [] as NightStep[],
  roleRules: [] as RoleRuleDraft[],
  sections: [] as SectionDraft[],
  specialRules: '',
  tips: '',
  faqs: [] as { question: string; answer: string }[],
})

const SECTION_PRESETS = ['警长竞选', '遗言', '平票处理', '自爆与自刀', '胜负判定']
const DIFFICULTIES = ['入门', '进阶', '烧脑']
const WIN_CONDITIONS = ['屠边', '屠城']

const isNew = computed(() => !props.boardId)
const rolesById = computed(() => new Map(roles.value.map((role) => [role.id, role])))
const size = computed(() => lineupSize(form.roles))
const support = computed(() => judgeSupport(form.roles, rolesById.value))
const autoOrderText = computed(() =>
  defaultNightOrder(form.roles, rolesById.value)
    .map((step) => (isWolfTeam(rolesById.value.get(step.roleId)) ? '狼队' : rolesById.value.get(step.roleId)?.name || ''))
    .join(' → '),
)
const sizeDiff = computed(() => size.value - form.playerCount)
const segments = computed(() => campSegments(form.roles, rolesById.value))
const lineup = computed(() =>
  sortLineup(form.roles, rolesById.value).map((item) => ({ ...item, role: rolesById.value.get(item.roleId) })),
)
const lineupIds = computed(() => new Set(form.roles.map((item) => item.roleId)))
const lineupRoles = computed(() => lineup.value.map((item) => item.role).filter((role): role is Role => Boolean(role)))
const pickerRoles = computed(() => roles.value.filter((role) => campOf(role) === pickerCamp.value))
const rolesForNotes = computed(() => lineupRoles.value.filter((role) => !form.roleRules.some((note) => note.roleId === role.id)))

/** 夜间顺序和角色细则里引用了、但已经不在阵容里的角色。 */
const outsideLineup = computed(() => {
  const ids = [...form.nightOrder.map((step) => step.roleId), ...form.roleRules.map((note) => note.roleId)]
  return [...new Set(ids.filter((id) => id && !lineupIds.value.has(id)))].map((id) => rolesById.value.get(id)?.name || `#${id}`)
})

const sizeState = computed(() => {
  if (!form.roles.length) return { tone: 'bad', text: '还没配角色' }
  if (sizeDiff.value === 0) return { tone: 'ok', text: `${size.value} / ${form.playerCount} 人，可以保存` }
  if (sizeDiff.value < 0) return { tone: 'bad', text: `${size.value} / ${form.playerCount} 人，还差 ${-sizeDiff.value} 人` }
  return { tone: 'bad', text: `${size.value} / ${form.playerCount} 人，多了 ${sizeDiff.value} 人` }
})

const blockers = computed(() => {
  const list: string[] = []
  if (!form.name.trim()) list.push('板子名称没填')
  if (!form.roles.length) list.push('阵容还没配角色')
  else if (sizeDiff.value !== 0) list.push(`阵容${sizeState.value.text.split('，')[1]}`)
  if (outsideLineup.value.length) list.push(`${outsideLineup.value.join('、')} 不在阵容里`)
  return list
})

const navItems = computed(() => [
  { id: 'sec-base', label: '基础信息' },
  { id: 'sec-art', label: '图片' },
  { id: 'sec-lineup', label: `阵容 ${size.value}/${form.playerCount}` },
  { id: 'sec-overview', label: '玩法概述' },
  { id: 'sec-night', label: `夜间顺序 ${form.nightOrder.length}` },
  { id: 'sec-notes', label: `角色细则 ${form.roleRules.length}` },
  { id: 'sec-sections', label: `规则章节 ${form.sections.length}` },
  { id: 'sec-more', label: '补充 · 贴士 · FAQ' },
])

/** 预览用的板子：把表单当前的样子拼成接口里的 Board。 */
const draftBoard = computed<Board>(() => ({
  id: form.id,
  name: form.name,
  playerCount: form.playerCount,
  difficulty: form.difficulty,
  tags: form.tags,
  coverImage: form.coverImage,
  backgroundImage: form.backgroundImage || null,
  cardDescription: form.cardDescription,
  briefConfig: '',
  specialRules: splitLines(form.specialRules),
  tips: splitLines(form.tips),
  faqs: form.faqs,
  ruleDetail: buildRuleDetail(),
  winCondition: form.winCondition,
  ruleType: form.ruleType,
  status: form.status,
  roles: form.roles,
}))

const featuredAt = computed(() => {
  if (!form.id) return 0
  const boards = allBoards.value.map((board) => (board.id === form.id ? draftBoard.value : board))
  return featuredBoards(boards).findIndex((board) => board.id === form.id) + 1
})

function splitLines(text: string) {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
}

function buildRuleDetail(): BoardRuleDetail {
  return {
    overview: form.overview.trim() || null,
    nightOrder: form.nightOrder
      .filter((step) => step.roleId && step.action.trim())
      .map((step) => ({ roleId: step.roleId, action: step.action.trim() })),
    roleRules: form.roleRules
      .map((note) => ({ roleId: note.roleId, items: splitLines(note.text) }))
      .filter((note) => note.roleId && note.items.length),
    sections: form.sections
      .map((section) => ({ title: section.title.trim(), items: splitLines(section.text) }))
      .filter((section) => section.title && section.items.length),
  }
}

function fill(board?: Board) {
  form.id = board?.id || 0
  form.name = board?.name || ''
  form.playerCount = board?.playerCount || 12
  form.difficulty = board?.difficulty || '入门'
  form.tags = [...(board?.tags || [])]
  form.coverImage = board?.coverImage || ''
  form.backgroundImage = board?.backgroundImage || ''
  form.cardDescription = board?.cardDescription || ''
  form.winCondition = board?.winCondition || '屠边'
  form.ruleType = board?.ruleType || '标准板'
  form.status = board?.status ?? 1
  form.roles = (board?.roles || []).map((item) => ({ roleId: item.roleId, count: item.count }))
  const detail = board?.ruleDetail
  form.overview = detail?.overview || ''
  form.nightOrder = (detail?.nightOrder || []).map((step) => ({ roleId: step.roleId, action: step.action }))
  form.roleRules = (detail?.roleRules || []).map((note) => ({ roleId: note.roleId, text: note.items.join('\n') }))
  form.sections = (detail?.sections || []).map((section) => ({ title: section.title, text: section.items.join('\n') }))
  form.specialRules = (board?.specialRules || []).join('\n')
  form.tips = (board?.tips || []).join('\n')
  form.faqs = (board?.faqs || []).map((faq) => ({ ...faq }))
  snapshot = JSON.stringify(form)
}

async function load() {
  loading.value = true
  try {
    const [boardData, roleData] = await Promise.all([api.getBoards(), api.getRoles()])
    allBoards.value = boardData
    roles.value = roleData
    if (props.boardId) {
      const board = boardData.find((item) => item.id === props.boardId)
      if (!board) {
        ElMessage.error('板子不存在，可能已经被删除')
        emit('close')
        return
      }
      fill(board)
    } else {
      fill()
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子加载失败')
  } finally {
    loading.value = false
  }
}

// ---------- 基础信息 ----------
function changePlayers(delta: number) {
  form.playerCount = Math.min(20, Math.max(4, form.playerCount + delta))
}

function addTag() {
  const value = tagDraft.value.trim()
  if (value && !form.tags.includes(value)) form.tags.push(value)
  tagDraft.value = ''
}

// ---------- 阵容 ----------
function addRole(role: Role) {
  const item = form.roles.find((entry) => entry.roleId === role.id)
  if (item) item.count += 1
  else form.roles.push({ roleId: role.id, count: 1 })
}

function changeCount(roleId: number, delta: number) {
  const index = form.roles.findIndex((entry) => entry.roleId === roleId)
  if (index < 0) return
  const next = form.roles[index].count + delta
  if (next <= 0) form.roles.splice(index, 1)
  else form.roles[index].count = Math.min(12, next)
}

function campCount(camp: CampKey) {
  return roles.value.filter((role) => campOf(role) === camp).length
}

// ---------- 夜间顺序 ----------
async function generateNightOrder() {
  if (form.nightOrder.length) {
    try {
      await ElMessageBox.confirm('会用通用顺序替换现在的夜间步骤。', '按通用顺序生成', {
        confirmButtonText: '替换',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  form.nightOrder = defaultNightOrder(form.roles, rolesById.value)
  if (!form.nightOrder.length) ElMessage.info('阵容里没有夜里要睁眼的角色')
}

function addNightStep() {
  form.nightOrder.push({ roleId: lineupRoles.value[0]?.id ?? 0, action: '' })
}

function moveStep(from: number, to: number) {
  if (to < 0 || to >= form.nightOrder.length || from === to) return
  const [step] = form.nightOrder.splice(from, 1)
  form.nightOrder.splice(to, 0, step)
}

function onDrop(index: number) {
  if (dragIndex.value !== null) moveStep(dragIndex.value, index)
  dragIndex.value = null
}

// ---------- 细则 / 章节 / FAQ ----------
function addRoleRule(event: Event) {
  const select = event.target as HTMLSelectElement
  const roleId = Number(select.value)
  if (roleId) form.roleRules.push({ roleId, text: '' })
  select.value = ''
}

function addSection(title = '') {
  if (title && form.sections.some((section) => section.title === title)) return
  form.sections.push({ title, text: '' })
}

function lineCount(text: string) {
  return splitLines(text).length
}

// ---------- 保存 / 状态 / 离开 ----------
function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function save() {
  if (blockers.value.length) {
    ElMessage.warning(`还不能保存：${blockers.value.join('；')}`)
    scrollTo(!form.name.trim() ? 'sec-base' : 'sec-lineup')
    return
  }
  const payload = {
    name: form.name.trim(),
    playerCount: form.playerCount,
    difficulty: form.difficulty,
    tags: form.tags,
    coverImage: form.coverImage,
    backgroundImage: form.backgroundImage || null,
    cardDescription: form.cardDescription,
    winCondition: form.winCondition,
    ruleType: form.ruleType,
    specialRules: splitLines(form.specialRules),
    tips: splitLines(form.tips),
    faqs: form.faqs.filter((faq) => faq.question.trim() && faq.answer.trim()),
    ruleDetail: buildRuleDetail(),
    roles: form.roles,
  }
  saving.value = true
  try {
    const saved = form.id ? await api.updateBoard(form.id, payload as Partial<Board>) : await api.createBoard(payload as Partial<Board>)
    ElMessage.success(form.id ? '板子已保存' : '板子已创建，已上架')
    emit('saved')
    if (!form.id) {
      snapshot = JSON.stringify(form)
      window.location.hash = `/boards/${saved.id}`
      return
    }
    allBoards.value = allBoards.value.map((board) => (board.id === saved.id ? saved : board))
    fill(saved)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus() {
  if (!form.id) return
  const online = form.status !== 1
  statusBusy.value = true
  try {
    const updated = await api.updateBoardStatus(form.id, online)
    form.status = updated.status
    snapshot = JSON.stringify({ ...JSON.parse(snapshot), status: updated.status })
    allBoards.value = allBoards.value.map((board) => (board.id === updated.id ? { ...board, status: updated.status } : board))
    ElMessage.success(online ? '已上架，小程序可见' : '已下架，小程序看不到了')
    emit('saved')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  } finally {
    statusBusy.value = false
  }
}

const dirty = computed(() => !loading.value && JSON.stringify(form) !== snapshot)

async function close() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('改动还没保存，离开会丢掉这些改动。', '离开编辑', {
        type: 'warning',
        confirmButtonText: '不保存，离开',
        cancelButtonText: '继续编辑',
      })
    } catch {
      return
    }
  }
  emit('close')
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault()
}

onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  await load()
  await nextTick()
})

onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <header class="head">
    <div class="head__title">
      <nav class="crumb" aria-label="位置">
        <a href="#/boards" @click.prevent="close">板子</a>
        <span>/</span>
        <span>{{ isNew ? '新建' : '编辑' }}</span>
      </nav>
      <h1>{{ form.name || (isNew ? '新建板子' : '未命名板子') }}</h1>
    </div>
    <div class="head__actions">
      <label v-if="!isNew" class="nt-switch status">
        <input type="checkbox" :checked="form.status === 1" :disabled="statusBusy" @change="toggleStatus" />
        <span class="nt-switch__track"></span>
        {{ form.status === 1 ? '上架中 · 小程序可见' : '已下架 · 小程序看不到' }}
      </label>
      <span v-else class="status status--plain">保存后直接上架</span>
      <button class="nt-btn nt-btn--ghost" type="button" @click="close">返回</button>
      <button class="nt-btn nt-btn--gold" type="button" :disabled="saving || loading" @click="save">{{ saving ? '保存中…' : '保存板子' }}</button>
    </div>
  </header>

  <nav class="jump" aria-label="编辑分区">
    <button v-for="item in navItems" :key="item.id" type="button" class="nt-chip" @click="scrollTo(item.id)">{{ item.label }}</button>
  </nav>

  <div v-if="loading" class="nt-empty">正在摊开板子…</div>

  <div v-else class="layout">
    <div class="form">
      <!-- 1 基础信息 -->
      <section id="sec-base" class="nt-panel">
        <h2 class="nt-h2"><span class="nt-num">1</span>基础信息</h2>
        <div class="grid-auto">
          <div class="nt-field">
            <label class="nt-label" for="b-name">板子名称</label>
            <input id="b-name" v-model="form.name" class="nt-input" maxlength="100" placeholder="比如：狼美人骑士" />
          </div>
          <div class="nt-field">
            <label class="nt-label" for="b-type">规则类型</label>
            <input id="b-type" v-model="form.ruleType" class="nt-input" placeholder="标准板" />
          </div>
          <div class="nt-field">
            <label class="nt-label" for="b-players">玩家人数</label>
            <div class="stepper">
              <button type="button" class="nt-iconbtn" aria-label="减少人数" @click="changePlayers(-1)">−</button>
              <input id="b-players" v-model.number="form.playerCount" class="nt-input" type="number" min="4" max="20" />
              <button type="button" class="nt-iconbtn" aria-label="增加人数" @click="changePlayers(1)">+</button>
            </div>
          </div>
          <div class="nt-field">
            <span class="nt-label">难度</span>
            <div class="nt-seg" role="radiogroup" aria-label="难度">
              <button v-for="level in DIFFICULTIES" :key="level" type="button" role="radio" :aria-checked="form.difficulty === level" :class="{ on: form.difficulty === level }" @click="form.difficulty = level">{{ level }}</button>
            </div>
          </div>
          <div class="nt-field">
            <span class="nt-label">胜利条件</span>
            <div class="nt-seg" role="radiogroup" aria-label="胜利条件">
              <button v-for="win in WIN_CONDITIONS" :key="win" type="button" role="radio" :aria-checked="form.winCondition === win" :class="{ on: form.winCondition === win }" @click="form.winCondition = win">{{ win }}</button>
            </div>
          </div>
          <div class="nt-field">
            <label class="nt-label" for="b-tag">标签</label>
            <div class="tags">
              <button v-for="(tag, index) in form.tags" :key="tag" type="button" class="nt-chip nt-chip--tag" :aria-label="`删除标签 ${tag}`" @click="form.tags.splice(index, 1)">{{ tag }} ×</button>
              <input id="b-tag" v-model="tagDraft" class="tags__input" placeholder="+ 标签，回车添加" maxlength="12" @keydown.enter.prevent="addTag" @blur="addTag" />
            </div>
          </div>
        </div>
        <div class="nt-field">
          <label class="nt-label" for="b-desc">列表简介 <small>· 显示在板子卡片底部，{{ form.cardDescription.length }} / 120</small></label>
          <textarea id="b-desc" v-model="form.cardDescription" class="nt-input" maxlength="120" rows="2" placeholder="一句话说清这个板子好玩在哪"></textarea>
        </div>
      </section>

      <!-- 2 图片 -->
      <section id="sec-art" class="nt-panel">
        <h2 class="nt-h2"><span class="nt-num">2</span>图片</h2>
        <div class="grid-art">
          <ImageField v-model="form.backgroundImage" name="backgroundImage" label="背景图" hint="横版，用于详情页顶部、列表卡片和首页推荐" />
          <ImageField v-model="form.coverImage" name="coverImage" label="封面图" hint="可选，没背景图时详情页用它" />
        </div>
      </section>

      <!-- 3 阵容 -->
      <section id="sec-lineup" class="nt-panel">
        <div class="nt-row-between">
          <h2 class="nt-h2"><span class="nt-num">3</span>阵容</h2>
          <span class="judge" :class="`judge--${JUDGE_SUPPORT_META[support.level].tone}`" :title="support.missing.length ? `${support.missing.join('、')} 没有技能机制` : ''">
            电子法官：{{ JUDGE_SUPPORT_META[support.level].label }}<template v-if="support.missing.length">（{{ support.missing.join('、') }} 没有机制）</template>
          </span>
          <span class="size" :class="`size--${sizeState.tone}`">
            <svg v-if="sizeState.tone === 'ok'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m5 12 5 5 9-10" /></svg>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" aria-hidden="true"><path d="M12 7v6M12 17h.01" /></svg>
            {{ sizeState.text }}
          </span>
        </div>
        <CampBar v-if="segments.length" :segments="segments" :height="10" />

        <div v-if="lineup.length" class="lineup">
          <div v-for="item in lineup" :key="item.roleId" class="lineup__item">
            <RoleToken :role="item.role" :size="44" ring />
            <div class="lineup__name">
              <strong>{{ item.role?.name || `#${item.roleId}` }}</strong>
              <span :style="{ color: CAMP_META[campOf(item.role)].color }">{{ CAMP_META[campOf(item.role)].label }}</span>
            </div>
            <div class="lineup__count">
              <button type="button" class="nt-iconbtn" :aria-label="`${item.role?.name} 减一`" @click="changeCount(item.roleId, -1)">−</button>
              <span>{{ item.count }}</span>
              <button type="button" class="nt-iconbtn" :aria-label="`${item.role?.name} 加一`" @click="changeCount(item.roleId, 1)">+</button>
            </div>
          </div>
        </div>

        <div class="picker">
          <div class="picker__head">
            <span class="nt-label">点角色加入阵容，再点一次加一张</span>
            <button v-for="camp in CAMP_ORDER" :key="camp" type="button" class="nt-chip" :class="{ 'nt-chip--on': pickerCamp === camp }" @click="pickerCamp = camp">
              <span class="nt-dot" :style="{ background: CAMP_META[camp].color }"></span>{{ CAMP_META[camp].label }} {{ campCount(camp) }}
            </button>
          </div>
          <div class="picker__list">
            <button v-for="role in pickerRoles" :key="role.id" type="button" class="pick" :class="{ 'pick--in': lineupIds.has(role.id) }" @click="addRole(role)">
              <RoleToken :role="role" :size="30" />
              {{ role.name }}
              <small v-if="lineupIds.has(role.id)">×{{ form.roles.find((entry) => entry.roleId === role.id)?.count }}</small>
            </button>
            <p v-if="!pickerRoles.length" class="nt-muted">这个阵营还没有角色，可以先去角色图鉴里新建。</p>
          </div>
        </div>
      </section>

      <!-- 4 玩法概述 -->
      <section id="sec-overview" class="nt-panel">
        <h2 class="nt-h2"><span class="nt-num">4</span>玩法概述</h2>
        <textarea v-model="form.overview" class="nt-input" rows="3" maxlength="2000" aria-label="玩法概述" placeholder="这个板子怎么玩：阵营构成、胜负条件、节奏特点"></textarea>
      </section>

      <!-- 5 夜间行动顺序 -->
      <section id="sec-night" class="nt-panel">
        <div class="nt-row-between">
          <h2 class="nt-h2"><span class="nt-num">5</span>夜间行动顺序</h2>
          <div class="btn-row">
            <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="!lineupRoles.length" @click="generateNightOrder">按通用顺序生成</button>
            <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="!lineupRoles.length" @click="addNightStep">+ 加一步</button>
          </div>
        </div>
        <p class="nt-muted">电子法官按这个顺序逐步引导，狼队（狼人、狼美人、白狼王等）会合并成一步。按住左边的把手拖动排序。不填就按角色的技能机制自动排：{{ autoOrderText || '阵容里还没有要睁眼的角色' }}。</p>
        <ol class="steps">
          <li
            v-for="(step, index) in form.nightOrder"
            :key="index"
            class="step"
            :class="{ 'step--drag': dragIndex === index }"
            @dragover.prevent
            @drop="onDrop(index)"
          >
            <span class="step__handle" draggable="true" title="拖动排序" @dragstart="dragIndex = index" @dragend="dragIndex = null">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="9" cy="6" r="1.6" /><circle cx="15" cy="6" r="1.6" /><circle cx="9" cy="12" r="1.6" /><circle cx="15" cy="12" r="1.6" /><circle cx="9" cy="18" r="1.6" /><circle cx="15" cy="18" r="1.6" /></svg>
            </span>
            <span class="nt-num step__no">{{ index + 1 }}</span>
            <RoleToken :role="rolesById.get(step.roleId)" />
            <select v-model.number="step.roleId" class="nt-input step__role" :aria-label="`第 ${index + 1} 步的角色`">
              <option v-for="role in lineupRoles" :key="role.id" :value="role.id">{{ role.name }}</option>
              <option v-if="step.roleId && !lineupIds.has(step.roleId)" :value="step.roleId">{{ rolesById.get(step.roleId)?.name || '已移除' }}（不在阵容）</option>
            </select>
            <input v-model="step.action" class="nt-input step__action" maxlength="200" :aria-label="`第 ${index + 1} 步的台词`" placeholder="这一步法官说什么、角色做什么" />
            <span class="step__ops">
              <button type="button" class="nt-iconbtn" aria-label="上移" :disabled="index === 0" @click="moveStep(index, index - 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 15 6-6 6 6" /></svg></button>
              <button type="button" class="nt-iconbtn" aria-label="下移" :disabled="index === form.nightOrder.length - 1" @click="moveStep(index, index + 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg></button>
              <button type="button" class="nt-iconbtn" aria-label="删除这一步" @click="form.nightOrder.splice(index, 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
            </span>
          </li>
        </ol>
        <p v-if="!form.nightOrder.length" class="nt-empty">还没配夜间顺序，法官会按通用顺序从阵容里挑</p>
      </section>

      <!-- 6 角色细则 -->
      <section id="sec-notes" class="nt-panel">
        <div class="nt-row-between">
          <h2 class="nt-h2"><span class="nt-num">6</span>角色细则</h2>
          <select class="nt-input add-select" aria-label="给哪个角色写细则" :disabled="!rolesForNotes.length" @change="addRoleRule">
            <option value="">+ 选角色</option>
            <option v-for="role in rolesForNotes" :key="role.id" :value="role.id">{{ role.name }}</option>
          </select>
        </div>
        <p class="nt-muted">这个板子里某个角色的特殊规定，一行一条。</p>
        <div class="notes">
          <div v-for="(note, index) in form.roleRules" :key="note.roleId" class="note" :style="{ borderTopColor: roleAccent(rolesById.get(note.roleId)) }">
            <div class="note__head">
              <RoleToken :role="rolesById.get(note.roleId)" />
              <strong>{{ rolesById.get(note.roleId)?.name || `#${note.roleId}` }}</strong>
              <small v-if="!lineupIds.has(note.roleId)" class="warn-text">不在阵容</small>
              <small>{{ lineCount(note.text) }} 条</small>
              <button type="button" class="nt-iconbtn" aria-label="删除这组细则" @click="form.roleRules.splice(index, 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
            </div>
            <textarea v-model="note.text" class="nt-input" rows="3" :aria-label="`${rolesById.get(note.roleId)?.name}细则，一行一条`" placeholder="例如：只有首夜可以自救"></textarea>
          </div>
        </div>
        <p v-if="!form.roleRules.length" class="nt-empty">没有角色细则</p>
      </section>

      <!-- 7 规则章节 -->
      <section id="sec-sections" class="nt-panel">
        <div class="nt-row-between">
          <h2 class="nt-h2"><span class="nt-num">7</span>规则章节</h2>
          <div class="btn-row">
            <button v-for="title in SECTION_PRESETS" :key="title" type="button" class="nt-chip" :disabled="form.sections.some((section) => section.title === title)" @click="addSection(title)">+ {{ title }}</button>
            <button type="button" class="nt-chip" @click="addSection()">+ 自定义</button>
          </div>
        </div>
        <div class="notes">
          <div v-for="(section, index) in form.sections" :key="index" class="note">
            <div class="note__head">
              <input v-model="section.title" class="nt-input note__title" maxlength="30" aria-label="章节标题" placeholder="章节标题" />
              <button type="button" class="nt-iconbtn" aria-label="删除这个章节" @click="form.sections.splice(index, 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
            </div>
            <textarea v-model="section.text" class="nt-input" rows="3" :aria-label="`${section.title || '章节'}规则，一行一条`" placeholder="一行写一条规则"></textarea>
          </div>
        </div>
        <p v-if="!form.sections.length" class="nt-empty">没有规则章节，点上面的按钮加</p>
      </section>

      <!-- 8 补充规则 / 小贴士 / FAQ -->
      <section id="sec-more" class="nt-panel extra">
        <div class="extra__item">
          <button type="button" class="extra__head" :aria-expanded="openExtra.special" @click="openExtra.special = !openExtra.special">
            <span class="nt-num nt-num--plain">8</span>补充规则<small>{{ lineCount(form.specialRules) }} 条 · 详情页放在章节后面</small>
            <svg class="extra__chev" :class="{ open: openExtra.special }" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </button>
          <textarea v-if="openExtra.special" v-model="form.specialRules" class="nt-input" rows="4" aria-label="补充规则，一行一条" placeholder="不属于任何章节的零散规则，一行一条"></textarea>
        </div>
        <div class="extra__item">
          <button type="button" class="extra__head" :aria-expanded="openExtra.tips" @click="openExtra.tips = !openExtra.tips">
            <span class="nt-num nt-num--plain">9</span>小贴士<small>{{ lineCount(form.tips) }} 条 · 没写列表简介时卡片用第一条</small>
            <svg class="extra__chev" :class="{ open: openExtra.tips }" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </button>
          <textarea v-if="openExtra.tips" v-model="form.tips" class="nt-input" rows="4" aria-label="小贴士，一行一条" placeholder="给新手的提示，一行一条"></textarea>
        </div>
        <div class="extra__item">
          <button type="button" class="extra__head" :aria-expanded="openExtra.faqs" @click="openExtra.faqs = !openExtra.faqs">
            <span class="nt-num nt-num--plain">10</span>常见问题<small>{{ form.faqs.length }} 组问答</small>
            <svg class="extra__chev" :class="{ open: openExtra.faqs }" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </button>
          <div v-if="openExtra.faqs" class="faqs">
            <div v-for="(faq, index) in form.faqs" :key="index" class="faq">
              <input v-model="faq.question" class="nt-input" :aria-label="`第 ${index + 1} 个问题`" placeholder="问题" />
              <textarea v-model="faq.answer" class="nt-input" rows="2" :aria-label="`第 ${index + 1} 个回答`" placeholder="回答"></textarea>
              <button type="button" class="nt-iconbtn" aria-label="删除这组问答" @click="form.faqs.splice(index, 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
            </div>
            <button type="button" class="nt-btn nt-btn--ghost nt-btn--small" @click="form.faqs.push({ question: '', answer: '' })">+ 加一组问答</button>
          </div>
        </div>
      </section>

      <div class="savebar">
        <p v-if="blockers.length" class="savebar__warn">还不能保存：{{ blockers.join('；') }}</p>
        <p v-else class="savebar__ok">保存时检查：阵容人数等于玩家人数、规则里的角色都在阵容中</p>
        <div class="btn-row">
          <button class="nt-btn nt-btn--ghost" type="button" @click="close">返回</button>
          <button class="nt-btn nt-btn--gold" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存板子' }}</button>
        </div>
      </div>
    </div>

    <BoardPreview class="preview" :board="draftBoard" :roles-by-id="rolesById" :featured-at="featuredAt" />
  </div>
</template>

<style scoped>
.judge {
  font-size: 12px;
}

.judge--ok {
  color: var(--moon);
}

.judge--warn {
  color: var(--gold);
}

.judge--off {
  color: var(--text-3);
}

.head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
}

.head__title {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.crumb {
  display: flex;
  gap: 8px;
  font-size: 14px;
  color: var(--text-3);
}

.crumb a {
  text-decoration: none;
}

.head h1 {
  margin: 0;
  font: 900 38px/1.12 var(--serif);
}

.head__actions,
.btn-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.status {
  height: 44px;
  padding: 0 14px;
  border-radius: 14px;
  background: var(--panel);
  border: 1px solid var(--panel-line);
  font-size: 14px;
  color: var(--text-2);
}

.status--plain {
  display: inline-flex;
  align-items: center;
}

.jump {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.layout {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  align-items: flex-start;
}

.form {
  flex: 999 1 520px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form > section {
  scroll-margin-top: 16px;
}

.preview {
  flex: 1 0 340px;
  max-width: 100%;
}

@media (min-width: 1200px) {
  .preview {
    position: sticky;
    top: 0;
  }
}

.grid-auto {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

.grid-art {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 18px;
}

.stepper {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stepper .nt-iconbtn {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  font-size: 18px;
}

.stepper .nt-input {
  text-align: center;
  font-weight: 900;
  font-size: 18px;
  -moz-appearance: textfield;
}

.stepper .nt-input::-webkit-inner-spin-button {
  display: none;
}

.tags {
  min-height: 44px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.tags__input {
  width: 130px;
  height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px dashed var(--line);
  background: transparent;
  color: var(--text);
  font-size: 13px;
  outline: 0;
}

.tags__input:focus {
  border-color: var(--moon);
}

.size {
  height: 32px;
  padding: 0 12px;
  border-radius: 16px;
  font-size: 14px;
  font-weight: 900;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.size--ok {
  background: var(--ok-bg);
  color: var(--camp-villager);
}

.size--bad {
  background: var(--danger-bg);
  color: #ffb1a9;
}

.lineup {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
}

.lineup__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 16px;
  background: var(--panel-raised);
  border: 2px solid var(--ink);
}

.lineup__name {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.lineup__name strong {
  font: 900 16px var(--serif);
}

.lineup__count {
  display: flex;
  align-items: center;
  gap: 6px;
}

.lineup__count span {
  min-width: 22px;
  text-align: center;
  font-weight: 900;
  font-size: 16px;
}

.picker {
  padding: 16px;
  border-radius: 16px;
  background: var(--bg);
  border: 1px dashed var(--line);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.picker__head,
.picker__list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.picker__head .nt-label {
  margin-right: 6px;
}

.picker__list {
  gap: 10px;
}

.pick {
  height: 40px;
  padding: 0 14px 0 4px;
  border-radius: 20px;
  border: 1px solid var(--line);
  background: var(--panel);
  color: var(--text);
  font: 600 14px var(--sans);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.pick:hover {
  border-color: var(--text-3);
}

.pick small {
  color: var(--camp-villager);
  font-weight: 900;
}

.pick--in {
  background: var(--ok-bg);
  border-color: #2b5c52;
}

.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.step {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--panel-raised);
  border: 2px solid transparent;
}

.step--drag {
  border-color: var(--moon);
  opacity: 0.6;
}

.step__handle {
  color: var(--text-3);
  cursor: grab;
  display: inline-flex;
}

.step__no {
  background: var(--moon);
}

.step__role {
  width: 120px;
  height: 40px;
}

.step__action {
  flex: 1 1 220px;
  height: 40px;
}

.step__ops {
  display: flex;
  gap: 6px;
}

.add-select {
  width: auto;
  min-width: 140px;
  height: 36px;
  font-size: 14px;
}

.notes {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
}

.note {
  padding: 14px;
  border-radius: 16px;
  background: var(--panel-raised);
  border-top: 4px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.note__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.note__head strong {
  font: 900 16px var(--serif);
}

.note__head small {
  font-size: 12px;
  color: var(--text-3);
}

.note__head .nt-iconbtn {
  margin-left: auto;
}

.note__title {
  font-weight: 700;
}

.warn-text {
  color: #ffb1a9 !important;
}

.extra {
  gap: 0;
  padding: 8px 24px;
}

.extra__item {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--panel-line);
}

.extra__item:last-child {
  border-bottom: 0;
}

.extra__head {
  height: 56px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font: 900 17px var(--serif);
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: left;
  cursor: pointer;
}

.extra__head small {
  font: 13px var(--sans);
  color: var(--text-3);
}

.nt-num--plain {
  background: var(--panel-raised);
  color: var(--text);
}

.extra__chev {
  margin-left: auto;
  color: var(--text-3);
  transition: transform 0.15s ease;
}

.extra__chev.open {
  transform: rotate(180deg);
}

.faqs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}

.faq {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr) 32px;
  gap: 10px;
  align-items: start;
}

.savebar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  padding: 16px 20px;
  border-radius: 18px;
  background: var(--bar-bg);
  border: 2px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
}

.savebar p {
  margin: 0;
  flex: 1 1 260px;
  font-size: 14px;
}

.savebar__ok {
  color: var(--text-2);
}

.savebar__warn {
  color: #ffb1a9;
}

@media (max-width: 720px) {
  .head h1 {
    font-size: 30px;
  }

  .faq {
    grid-template-columns: 1fr;
  }
}
</style>
