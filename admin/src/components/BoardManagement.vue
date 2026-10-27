<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { api } from '../services/api'
import type { Board, BoardRuleDetail, NightStep, Role } from '../types'
import { clampPage, paginate } from '../utils/pagination'

const emit = defineEmits<{ changed: [] }>()

const loading = ref(false)
const dialogVisible = ref(false)
const boards = ref<Board[]>([])
const roles = ref<Role[]>([])

const keyword = ref('')
const difficultyFilter = ref('ALL')
const playerCountFilter = ref<number | 'ALL'>('ALL')
const currentPage = ref(1)
const pageSize = ref(8)

const difficultyOptions = ['ALL', '入门', '进阶', '烧脑']

const emptyBoard = (): Board => ({
  id: 0,
  name: '',
  playerCount: 12,
  difficulty: '入门',
  tags: ['经典'],
  coverImage: '',
  backgroundImage: '',
  cardDescription: '',
  briefConfig: '',
  specialRules: [''],
  tips: [''],
  faqs: [{ question: '', answer: '' }],
  ruleDetail: { overview: '', nightOrder: [], roleRules: [], sections: [] },
  winCondition: '屠边',
  ruleType: '标准板',
  status: 1,
  roles: [{ roleId: 0, count: 1 }],
})

const form = reactive<Board>(emptyBoard())

/**
 * 详细规则的编辑草稿：角色细则和章节的条目在表单里用多行文本编辑（一行一条），
 * 提交时再拆成数组。
 */
interface RoleRuleDraft {
  roleId: number
  text: string
}

interface SectionDraft {
  title: string
  text: string
}

const ruleDraft = reactive({
  overview: '',
  nightOrder: [] as NightStep[],
  roleRules: [] as RoleRuleDraft[],
  sections: [] as SectionDraft[],
})

const sectionPresets = ['警长竞选', '遗言', '平票处理', '自爆与自刀', '胜负判定']

function loadRuleDraft(detail?: BoardRuleDetail | null) {
  ruleDraft.overview = detail?.overview || ''
  ruleDraft.nightOrder = (detail?.nightOrder || []).map((step) => ({ roleId: step.roleId, action: step.action }))
  ruleDraft.roleRules = (detail?.roleRules || []).map((note) => ({ roleId: note.roleId, text: note.items.join('\n') }))
  ruleDraft.sections = (detail?.sections || []).map((section) => ({ title: section.title, text: section.items.join('\n') }))
}

function splitLines(text: string) {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
}

function buildRuleDetail(): BoardRuleDetail {
  return {
    overview: ruleDraft.overview.trim() || null,
    nightOrder: ruleDraft.nightOrder
      .filter((step) => step.roleId && step.action.trim())
      .map((step) => ({ roleId: step.roleId, action: step.action.trim() })),
    roleRules: ruleDraft.roleRules
      .map((note) => ({ roleId: note.roleId, items: splitLines(note.text) }))
      .filter((note) => note.roleId && note.items.length),
    sections: ruleDraft.sections
      .map((section) => ({ title: section.title.trim(), items: splitLines(section.text) }))
      .filter((section) => section.title && section.items.length),
  }
}

/** 夜间顺序和角色细则只能选本板子阵容里已有的角色。 */
const lineupRoleOptions = computed(() => {
  const seen = new Set<number>()
  return form.roles
    .filter((item) => item.roleId && !seen.has(item.roleId) && seen.add(item.roleId))
    .map((item) => rolesById.value.get(item.roleId))
    .filter((role): role is Role => Boolean(role))
    .map((role) => ({ label: role.name, value: role.id }))
})

const rulesOutsideLineup = computed(() => {
  const lineup = new Set(lineupRoleOptions.value.map((option) => option.value))
  const referenced = [
    ...ruleDraft.nightOrder.map((step) => step.roleId),
    ...ruleDraft.roleRules.map((note) => note.roleId),
  ].filter(Boolean)
  return [...new Set(referenced.filter((roleId) => !lineup.has(roleId)))]
    .map((roleId) => rolesById.value.get(roleId)?.name || `#${roleId}`)
})

function addNightStep() {
  ruleDraft.nightOrder.push({ roleId: lineupRoleOptions.value[0]?.value ?? 0, action: '' })
}

function moveNightStep(index: number, offset: -1 | 1) {
  const target = index + offset
  if (target < 0 || target >= ruleDraft.nightOrder.length) {
    return
  }
  const [step] = ruleDraft.nightOrder.splice(index, 1)
  ruleDraft.nightOrder.splice(target, 0, step)
}

function addRoleRule() {
  const used = new Set(ruleDraft.roleRules.map((note) => note.roleId))
  const next = lineupRoleOptions.value.find((option) => !used.has(option.value))
  ruleDraft.roleRules.push({ roleId: next?.value ?? 0, text: '' })
}

function addSection(title = '') {
  if (title && ruleDraft.sections.some((section) => section.title === title)) {
    return
  }
  ruleDraft.sections.push({ title, text: '' })
}

const rolesById = computed(() => new Map(roles.value.map((role) => [role.id, role])))

const playerCountOptions = computed(() =>
  [...new Set(boards.value.map((board) => Number(board.playerCount)).filter(Boolean))].sort((a, b) => a - b),
)

const roleOptions = computed(() =>
  roles.value.map((role) => ({
    label: `${role.name} · ${role.faction}/${role.roleType}`,
    value: role.id,
  })),
)

const configuredPlayers = computed(() =>
  form.roles.reduce((sum, item) => sum + (Number(item.count) || 0), 0),
)

const lineupPreview = computed(() =>
  form.roles
    .filter((item) => item.roleId && item.count > 0)
    .map((item) => {
      const role = rolesById.value.get(item.roleId)
      if (!role) {
        return ''
      }
      if (item.count > 1 || role.faction !== '好人' || role.roleType === '平民') {
        return `${item.count}${role.name}`
      }
      return role.name
    })
    .filter(Boolean)
    .join(' '),
)

const cardDescriptionPreview = computed(() =>
  form.cardDescription?.trim()
  || form.tips.find((item) => item && item.trim())
  || form.specialRules.find((item) => item && item.trim())
  || '卡片底部描述会优先显示这里填写的列表简介，未填写时会回退到提示或规则说明。',
)

const filteredBoards = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return boards.value.filter((board) => {
    const searchableText = [
      board.name,
      board.cardDescription,
      board.briefConfig,
      board.ruleType,
      board.tags.join(' '),
    ]
      .join(' ')
      .toLowerCase()

    const matchesKeyword = !normalizedKeyword || searchableText.includes(normalizedKeyword)
    const matchesDifficulty = difficultyFilter.value === 'ALL' || board.difficulty === difficultyFilter.value
    const matchesPlayerCount = playerCountFilter.value === 'ALL' || board.playerCount === playerCountFilter.value
    return matchesKeyword && matchesDifficulty && matchesPlayerCount
  })
})

const pagedBoards = computed(() => paginate(filteredBoards.value, currentPage.value, pageSize.value))

watch([keyword, difficultyFilter, playerCountFilter, pageSize], () => {
  currentPage.value = 1
})

watch(
  () => filteredBoards.value.length,
  (total) => {
    currentPage.value = clampPage(currentPage.value, pageSize.value, total)
  },
)

function normalizeBoard(board?: Board): Board {
  if (!board) {
    return emptyBoard()
  }

  const next = JSON.parse(JSON.stringify(board)) as Board
  next.cardDescription = next.cardDescription || ''
  next.backgroundImage = next.backgroundImage || ''
  next.tags = next.tags?.length ? next.tags : ['经典']
  next.specialRules = next.specialRules?.length ? next.specialRules : ['']
  next.tips = next.tips?.length ? next.tips : ['']
  next.faqs = next.faqs?.length ? next.faqs : [{ question: '', answer: '' }]
  next.roles = next.roles?.length ? next.roles : [{ roleId: roleOptions.value[0]?.value ?? 0, count: 1 }]
  return next
}

function getBoardDescription(board: Board) {
  return (
    board.cardDescription?.trim()
    || board.tips.find((item) => item && item.trim())
    || board.specialRules.find((item) => item && item.trim())
    || '未填写列表简介'
  )
}

function resetForm(board?: Board) {
  Object.assign(form, normalizeBoard(board))
  loadRuleDraft(board?.ruleDetail)
}

function resetFilters() {
  keyword.value = ''
  difficultyFilter.value = 'ALL'
  playerCountFilter.value = 'ALL'
  currentPage.value = 1
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(board: Board) {
  resetForm(board)
  dialogVisible.value = true
}

async function load() {
  loading.value = true
  try {
    const [boardData, roleData] = await Promise.all([api.getBoards(), api.getRoles()])
    boards.value = boardData
    roles.value = roleData
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子数据加载失败')
  } finally {
    loading.value = false
  }
}

function addStringItem(list: string[]) {
  list.push('')
}

function addFaq() {
  form.faqs.push({ question: '', answer: '' })
}

function addRole() {
  form.roles.push({ roleId: roleOptions.value[0]?.value ?? 0, count: 1 })
}

function removeListItem<T>(list: T[], index: number, fallback: T) {
  if (list.length === 1) {
    list.splice(0, 1, fallback)
    return
  }
  list.splice(index, 1)
}

function updateTags(value: string) {
  form.tags = value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

async function submit() {
  if (rulesOutsideLineup.value.length) {
    ElMessage.warning(`详细规则里的 ${rulesOutsideLineup.value.join('、')} 不在角色配置中，请先加入阵容或删掉对应规则`)
    return
  }
  const payload = {
    ...form,
    backgroundImage: form.backgroundImage || null,
    specialRules: form.specialRules.filter(Boolean),
    tips: form.tips.filter(Boolean),
    faqs: form.faqs.filter((faq) => faq.question && faq.answer),
    ruleDetail: buildRuleDetail(),
    roles: form.roles.filter((role) => role.roleId && role.count > 0),
  }

  try {
    if (form.id) {
      await api.updateBoard(form.id, payload)
      ElMessage.success('板子已更新')
    } else {
      await api.createBoard(payload)
      ElMessage.success('板子已创建')
    }
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子保存失败')
  }
}

async function removeBoard(id: number) {
  try {
    await ElMessageBox.confirm('将同时删除该板子的角色配置；引用它的法官房间将无法开局。此操作不可恢复。', '删除板子', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api.deleteBoard(id)
    ElMessage.success('板子已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子删除失败')
  }
}

async function uploadImageTo(target: 'coverImage' | 'backgroundImage', options: UploadRequestOptions) {
  const label = target === 'coverImage' ? '封面' : '背景图'
  try {
    const result = await api.upload(options.file as File)
    form[target] = result.url
    ElMessage.success(`${label}已上传`)
    options.onSuccess?.(result)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${label}上传失败`)
    options.onError?.(Object.assign(new Error('upload failed'), { status: 0, method: 'post', url: '' }) as never)
  }
}

function uploadCover(options: UploadRequestOptions) {
  return uploadImageTo('coverImage', options)
}

function uploadBackground(options: UploadRequestOptions) {
  return uploadImageTo('backgroundImage', options)
}

onMounted(load)
</script>

<template>
  <el-card class="panel-card">
    <div class="table-toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索板子名称、标签、规则或列表简介" />
      <el-select v-model="difficultyFilter">
        <el-option
          v-for="item in difficultyOptions"
          :key="item"
          :label="item === 'ALL' ? '全部难度' : item"
          :value="item"
        />
      </el-select>
      <el-select v-model="playerCountFilter">
        <el-option label="全部人数" value="ALL" />
        <el-option v-for="count in playerCountOptions" :key="count" :label="`${count} 人`" :value="count" />
      </el-select>
      <el-button @click="resetFilters">重置</el-button>
      <div class="toolbar-summary">当前 {{ filteredBoards.length }} 条</div>
      <el-button type="primary" @click="openCreate">新建板子</el-button>
    </div>

    <el-table :data="pagedBoards" v-loading="loading" empty-text="暂无符合条件的板子">
      <el-table-column prop="name" label="板子名称" min-width="180" fixed="left" show-overflow-tooltip />
      <el-table-column prop="playerCount" label="人数" width="90" />
      <el-table-column prop="difficulty" label="难度" width="100" />
      <el-table-column label="标签" min-width="180">
        <template #default="{ row }">
          <div class="tag-row">
            <el-tag v-for="tag in row.tags" :key="tag" effect="plain" type="warning">{{ tag }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="列表简介" min-width="280">
        <template #default="{ row }">
          <div class="description-cell" :title="getBoardDescription(row)">
            {{ getBoardDescription(row) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button text type="danger" @click="removeBoard(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <div class="table-total">共 {{ filteredBoards.length }} 条板子</div>
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        background
        size="small"
        layout="sizes, prev, pager, next"
        :pager-count="5"
        :page-sizes="[6, 8, 10, 20]"
        :total="filteredBoards.length"
      />
    </div>
  </el-card>

  <el-dialog v-model="dialogVisible" width="1040px" :title="form.id ? '编辑板子' : '新建板子'">
    <div class="preview-card">
      <div class="preview-layout">
        <div>
          <div class="preview-meta">Preview</div>
          <div class="preview-title">{{ form.name || '未命名板子' }}</div>
          <div class="preview-line">
            <span>{{ form.playerCount }} 人局</span>
            <span>{{ form.difficulty }}</span>
            <span>{{ form.ruleType || '标准板' }}</span>
          </div>
          <div class="preview-lineup">{{ lineupPreview || '请先配置角色阵容' }}</div>
          <div class="preview-desc">{{ cardDescriptionPreview }}</div>
          <div class="preview-count" :class="{ warning: configuredPlayers !== Number(form.playerCount) }">
            当前配置 {{ configuredPlayers }} 人 / 目标 {{ form.playerCount }} 人
          </div>
        </div>

        <div class="media-preview-card">
          <div class="media-preview-label">Cover Preview</div>
          <div class="media-preview-frame media-preview-frame--board" :class="{ 'is-empty': !form.coverImage }">
            <img v-if="form.coverImage" :src="form.coverImage" alt="板子封面预览" />
            <span v-else>上传后在这里预览封面</span>
          </div>
        </div>
      </div>
    </div>

    <el-form label-position="top">
      <div class="form-grid">
        <el-form-item label="板子名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="玩家人数">
          <el-input-number v-model="form.playerCount" :min="6" :max="20" />
        </el-form-item>
        <el-form-item label="难度">
          <el-select v-model="form.difficulty">
            <el-option label="入门" value="入门" />
            <el-option label="进阶" value="进阶" />
            <el-option label="烧脑" value="烧脑" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则类型">
          <el-input v-model="form.ruleType" />
        </el-form-item>
      </div>

      <div class="form-grid">
        <el-form-item label="标签（逗号分隔）">
          <el-input :model-value="form.tags.join(', ')" @update:model-value="updateTags" />
        </el-form-item>
        <el-form-item label="胜利条件">
          <el-input v-model="form.winCondition" />
        </el-form-item>
        <el-form-item class="span-2" label="列表简介">
          <el-input
            v-model="form.cardDescription"
            type="textarea"
            :rows="2"
            maxlength="120"
            show-word-limit
            placeholder="用于板子列表卡片底部的简短介绍"
          />
        </el-form-item>
        <el-form-item class="span-2" label="封面图">
          <div class="upload-panel">
            <div class="upload-stack">
              <el-input v-model="form.coverImage" placeholder="可直接填写图片 URL" />
              <el-upload :show-file-list="false" :http-request="uploadCover">
                <el-button>上传图片</el-button>
              </el-upload>
            </div>
            <div v-if="form.coverImage" class="inline-image-preview inline-image-preview--board">
              <img :src="form.coverImage" alt="板子封面预览" />
            </div>
          </div>
        </el-form-item>
        <el-form-item class="span-2" label="详情页背景图（横版 16:9，留空则用封面图）">
          <div class="upload-panel">
            <div class="upload-stack">
              <el-input v-model="form.backgroundImage" placeholder="可直接填写图片 URL" />
              <el-upload :show-file-list="false" :http-request="uploadBackground">
                <el-button>上传图片</el-button>
              </el-upload>
            </div>
            <div v-if="form.backgroundImage" class="inline-image-preview inline-image-preview--background">
              <img :src="form.backgroundImage" alt="板子背景图预览" />
            </div>
          </div>
        </el-form-item>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>角色配置</strong>
          <el-button text type="primary" @click="addRole">添加角色</el-button>
        </div>
        <div class="role-config-grid">
          <div v-for="(item, index) in form.roles" :key="index" class="role-config-row">
            <div class="role-config-main">
              <div class="role-field-label">角色</div>
              <el-select v-model="item.roleId" placeholder="选择角色" filterable>
                <el-option v-for="role in roleOptions" :key="role.value" :label="role.label" :value="role.value" />
              </el-select>
            </div>
            <div class="role-config-count">
              <div class="role-field-label">数量</div>
              <div class="count-shell">
                <el-input-number v-model="item.count" :min="1" :max="12" />
              </div>
            </div>
            <div class="role-config-actions">
              <div class="role-field-label role-field-label--ghost">操作</div>
              <el-button
                class="role-delete-button"
                text
                type="danger"
                @click="removeListItem(form.roles, index, { roleId: 0, count: 1 })"
              >
                删除
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>详细规则</strong>
        </div>
        <div class="array-note">小程序板子详情页按「玩法概述 → 夜间行动顺序 → 角色细则 → 规则章节」展示。角色只能从上面的角色配置里选。</div>
        <el-alert
          v-if="rulesOutsideLineup.length"
          class="rule-alert"
          type="warning"
          :closable="false"
          :title="`${rulesOutsideLineup.join('、')} 已不在角色配置中，保存前请调整`"
        />

        <el-form-item label="玩法概述">
          <el-input
            v-model="ruleDraft.overview"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            placeholder="这个板子怎么玩：阵营构成、胜负条件、节奏特点"
          />
        </el-form-item>

        <div class="rule-subheader">
          <span>夜间行动顺序</span>
          <el-button text type="primary" :disabled="!lineupRoleOptions.length" @click="addNightStep">添加一步</el-button>
        </div>
        <div v-for="(step, index) in ruleDraft.nightOrder" :key="`night-${index}`" class="night-step-row">
          <span class="night-step-order">{{ index + 1 }}</span>
          <el-select v-model="step.roleId" placeholder="角色">
            <el-option v-for="option in lineupRoleOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input v-model="step.action" maxlength="200" placeholder="这一步法官说什么、角色做什么" />
          <div class="night-step-actions">
            <el-button text :disabled="index === 0" @click="moveNightStep(index, -1)">上移</el-button>
            <el-button text :disabled="index === ruleDraft.nightOrder.length - 1" @click="moveNightStep(index, 1)">下移</el-button>
            <el-button text type="danger" @click="ruleDraft.nightOrder.splice(index, 1)">删除</el-button>
          </div>
        </div>

        <div class="rule-subheader">
          <span>角色细则（一行一条）</span>
          <el-button text type="primary" :disabled="!lineupRoleOptions.length" @click="addRoleRule">添加角色</el-button>
        </div>
        <div v-for="(note, index) in ruleDraft.roleRules" :key="`note-${index}`" class="rule-block-row">
          <el-select v-model="note.roleId" placeholder="角色">
            <el-option v-for="option in lineupRoleOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input v-model="note.text" type="textarea" :rows="3" placeholder="例如：只有首夜可以自救" />
          <el-button text type="danger" @click="ruleDraft.roleRules.splice(index, 1)">删除</el-button>
        </div>

        <div class="rule-subheader">
          <span>规则章节（一行一条）</span>
          <div class="section-presets">
            <el-button
              v-for="title in sectionPresets"
              :key="title"
              size="small"
              :disabled="ruleDraft.sections.some((section) => section.title === title)"
              @click="addSection(title)"
            >
              + {{ title }}
            </el-button>
            <el-button text type="primary" @click="addSection()">自定义章节</el-button>
          </div>
        </div>
        <div v-for="(section, index) in ruleDraft.sections" :key="`section-${index}`" class="rule-block-row">
          <el-input v-model="section.title" maxlength="30" placeholder="章节标题" />
          <el-input v-model="section.text" type="textarea" :rows="3" placeholder="一行写一条规则" />
          <el-button text type="danger" @click="ruleDraft.sections.splice(index, 1)">删除</el-button>
        </div>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>补充规则</strong>
          <el-button text type="primary" @click="addStringItem(form.specialRules)">新增规则</el-button>
        </div>
        <div class="array-note">不属于上面任何章节的零散规则，详情页会放在规则章节后面。</div>
        <div v-for="(_, index) in form.specialRules" :key="`rule-${index}`" class="inline-row single-input-row">
          <el-input v-model="form.specialRules[index]" />
          <el-button text type="danger" @click="removeListItem(form.specialRules, index, '')">删除</el-button>
        </div>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>小贴士</strong>
          <el-button text type="primary" @click="addStringItem(form.tips)">新增提示</el-button>
        </div>
        <div class="array-note">板子卡片底部描述会优先读取这里的第一条内容。</div>
        <div v-for="(_, index) in form.tips" :key="`tip-${index}`" class="inline-row single-input-row">
          <el-input v-model="form.tips[index]" />
          <el-button text type="danger" @click="removeListItem(form.tips, index, '')">删除</el-button>
        </div>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>常见问题</strong>
          <el-button text type="primary" @click="addFaq">新增 FAQ</el-button>
        </div>
        <div v-for="(faq, index) in form.faqs" :key="`faq-${index}`" class="faq-row">
          <el-input v-model="faq.question" placeholder="问题" />
          <el-input v-model="faq.answer" type="textarea" :rows="2" placeholder="答案" />
          <el-button text type="danger" @click="removeListItem(form.faqs, index, { question: '', answer: '' })">
            删除
          </el-button>
        </div>
      </div>
    </el-form>

    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="submit">保存板子</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:deep(.el-dialog) {
  max-height: calc(100vh - 40px);
  display: flex;
  flex-direction: column;
}

:deep(.el-dialog__header),
:deep(.el-dialog__footer) {
  flex: 0 0 auto;
}

:deep(.el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 192, 0, 0.42) rgba(255, 255, 255, 0.05);
}

:deep(.el-dialog__body::-webkit-scrollbar) {
  width: 8px;
}

:deep(.el-dialog__body::-webkit-scrollbar-track) {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 999px;
}

:deep(.el-dialog__body::-webkit-scrollbar-thumb) {
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 211, 92, 0.72), rgba(182, 126, 12, 0.74));
}

.panel-card {
  border-radius: 18px;
}

.table-toolbar {
  margin-bottom: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  padding: 12px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.02);
}

.table-toolbar > :nth-child(1) {
  flex: 1 1 360px;
  min-width: 280px;
}

.table-toolbar > :nth-child(2),
.table-toolbar > :nth-child(3) {
  flex: 0 0 150px;
}

.table-toolbar > :nth-child(4) {
  flex: 0 0 90px;
}

.table-toolbar > :nth-child(5) {
  margin-left: auto;
  flex: 0 0 auto;
}

.table-toolbar > :nth-child(6) {
  flex: 0 0 auto;
}

.toolbar-summary {
  color: #8d8d8d;
  font-size: 13px;
  white-space: nowrap;
}

.description-cell {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  color: #cfcfcf;
  line-height: 1.65;
}

.table-footer {
  margin-top: 14px;
  padding-top: 14px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.table-total {
  color: #8d8d8d;
  font-size: 13px;
}

.table-footer :deep(.el-pagination) {
  margin-left: auto;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preview-card {
  margin-bottom: 24px;
  padding: 20px;
  border: 1px solid rgba(255, 192, 0, 0.18);
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(255, 192, 0, 0.1), rgba(255, 255, 255, 0.02));
}

.preview-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) 260px;
  gap: 18px;
  align-items: start;
}

.preview-meta {
  color: #ffc000;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.preview-title {
  margin-top: 10px;
  font-size: 28px;
  font-weight: 700;
}

.preview-line {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #cfcfcf;
}

.preview-lineup {
  margin-top: 14px;
  font-size: 18px;
  line-height: 1.7;
}

.preview-count {
  margin-top: 12px;
  color: #ffc000;
  font-size: 13px;
}

.preview-desc {
  margin-top: 10px;
  color: #d6d6d6;
  line-height: 1.7;
}

.media-preview-card {
  display: grid;
  gap: 10px;
}

.media-preview-label {
  color: #f5d069;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.media-preview-frame {
  position: relative;
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid rgba(255, 192, 0, 0.16);
  background: linear-gradient(180deg, rgba(28, 28, 28, 0.94), rgba(12, 12, 12, 0.94));
}

.media-preview-frame::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.04), transparent 32%, rgba(0, 0, 0, 0.16));
}

.media-preview-frame--board {
  aspect-ratio: 4 / 3;
}

.media-preview-frame img,
.inline-image-preview img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.media-preview-frame.is-empty {
  display: grid;
  place-items: center;
  min-height: 180px;
  padding: 18px;
  color: #8d8d8d;
  text-align: center;
  line-height: 1.6;
}

.preview-count.warning {
  color: #ff725e;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.span-2 {
  grid-column: span 2;
}

.upload-panel {
  display: grid;
  gap: 12px;
}

.inline-image-preview {
  overflow: hidden;
  border-radius: 14px;
  border: 1px solid rgba(255, 192, 0, 0.14);
  background: rgba(255, 255, 255, 0.02);
}

.inline-image-preview--board {
  max-width: 320px;
  aspect-ratio: 16 / 10;
}

.inline-image-preview--background {
  max-width: 420px;
  aspect-ratio: 16 / 9;
}

.rule-alert {
  margin-bottom: 14px;
}

.rule-subheader {
  margin: 18px 0 10px;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  color: #f5d069;
  font-size: 13px;
  font-weight: 700;
}

.section-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.section-presets :deep(.el-button + .el-button) {
  margin-left: 0;
}

.night-step-row {
  display: grid;
  grid-template-columns: 32px 160px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  margin-bottom: 10px;
}

.night-step-order {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
  font-weight: 700;
}

.night-step-actions {
  display: flex;
  gap: 2px;
}

.night-step-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.rule-block-row {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr) 70px;
  gap: 12px;
  align-items: start;
  margin-bottom: 12px;
}

.array-section {
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.array-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.array-note {
  margin-bottom: 10px;
  color: #8d8d8d;
  font-size: 12px;
  line-height: 1.6;
}

.role-config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.role-config-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 200px 72px;
  gap: 14px;
  align-items: end;
  padding: 14px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.025), rgba(255, 255, 255, 0.015));
}

.role-config-main,
.role-config-count,
.role-config-actions {
  display: grid;
  gap: 8px;
}

.role-field-label {
  color: #8d8d8d;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.role-field-label--ghost {
  opacity: 0;
  pointer-events: none;
}

.count-shell :deep(.el-input-number) {
  width: 100%;
}

.count-shell :deep(.el-input-number__decrease),
.count-shell :deep(.el-input-number__increase) {
  width: 44px;
}

.count-shell :deep(.el-input__inner) {
  text-align: center;
  font-weight: 700;
}

.role-config-actions {
  align-items: stretch;
}

.role-delete-button {
  min-height: 48px;
  justify-content: center;
}

.inline-row {
  display: grid;
  grid-template-columns: 1fr 120px 70px;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.single-input-row {
  grid-template-columns: 1fr 70px;
}

.faq-row {
  display: grid;
  grid-template-columns: 1fr 1.4fr 70px;
  gap: 12px;
  align-items: start;
  margin-bottom: 12px;
}

.upload-stack {
  display: flex;
  gap: 12px;
}

@media (max-width: 1080px) {
  .table-toolbar > :nth-child(1) {
    flex-basis: 100%;
  }

  .table-toolbar > :nth-child(5) {
    margin-left: 0;
  }

  .table-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 900px) {
  .preview-layout,
  .form-grid,
  .faq-row,
  .inline-row,
  .single-input-row,
  .role-config-grid,
  .role-config-row,
  .night-step-row,
  .rule-block-row {
    grid-template-columns: 1fr;
  }

  .table-toolbar {
    display: grid;
    grid-template-columns: 1fr;
  }

  .table-toolbar > :nth-child(1),
  .table-toolbar > :nth-child(2),
  .table-toolbar > :nth-child(3),
  .table-toolbar > :nth-child(4),
  .table-toolbar > :nth-child(5),
  .table-toolbar > :nth-child(6),
  .toolbar-summary {
    flex: initial;
    min-width: 0;
    margin-left: 0;
  }

  .upload-stack {
    flex-direction: column;
  }

  .span-2 {
    grid-column: span 1;
  }

  .role-field-label--ghost {
    display: none;
  }
}
</style>
