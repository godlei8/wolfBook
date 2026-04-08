<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { api } from '../services/api'
import type { Board, Role } from '../types'

const emit = defineEmits<{ changed: [] }>()

const loading = ref(false)
const dialogVisible = ref(false)
const boards = ref<Board[]>([])
const roles = ref<Role[]>([])

const emptyBoard = (): Board => ({
  id: 0,
  name: '',
  playerCount: 12,
  difficulty: '入门',
  tags: ['经典'],
  coverImage: '',
  cardDescription: '',
  briefConfig: '',
  specialRules: [''],
  tips: [''],
  faqs: [{ question: '', answer: '' }],
  winCondition: '屠边',
  ruleType: '标准板',
  status: 1,
  roles: [{ roleId: 0, count: 1 }],
})

const form = reactive<Board>(emptyBoard())

const rolesById = computed(() => new Map(roles.value.map((role) => [role.id, role])))

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

const briefConfigPreview = computed(() =>
  form.roles
    .filter((item) => item.roleId && item.count > 0)
    .map((item) => {
      const role = rolesById.value.get(item.roleId)
      return role ? `${role.name}x${item.count}` : ''
    })
    .filter(Boolean)
    .join(' '),
)

const cardDescriptionPreview = computed(() =>
  form.cardDescription?.trim()
  || form.tips.find((item) => item && item.trim())
  || form.specialRules.find((item) => item && item.trim())
  || '卡片底部描述会优先显示首条小贴士，若为空则显示首条规则说明'
)

function normalizeBoard(board?: Board): Board {
  if (!board) {
    return emptyBoard()
  }
  const next = JSON.parse(JSON.stringify(board)) as Board
  next.cardDescription = next.cardDescription || ''
  next.tags = next.tags?.length ? next.tags : ['经典']
  next.specialRules = next.specialRules?.length ? next.specialRules : ['']
  next.tips = next.tips?.length ? next.tips : ['']
  next.faqs = next.faqs?.length ? next.faqs : [{ question: '', answer: '' }]
  next.roles = next.roles?.length ? next.roles : [{ roleId: roleOptions.value[0]?.value ?? 0, count: 1 }]
  return next
}

function resetForm(board?: Board) {
  Object.assign(form, normalizeBoard(board))
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
  const payload = {
    ...form,
    specialRules: form.specialRules.filter(Boolean),
    tips: form.tips.filter(Boolean),
    faqs: form.faqs.filter((faq) => faq.question && faq.answer),
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
    await api.deleteBoard(id)
    ElMessage.success('板子已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子删除失败')
  }
}

async function uploadCover(options: UploadRequestOptions) {
  try {
    const result = await api.upload(options.file as File)
    form.coverImage = result.url
    options.onSuccess?.(result)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '封面上传失败')
    options.onError?.(Object.assign(new Error('upload failed'), { status: 0, method: 'post', url: '' }) as never)
  }
}

onMounted(load)
</script>

<template>
  <el-card class="panel-card">
    <template #header>
      <div class="panel-header">
        <div>
          <div class="panel-kicker">Boards</div>
          <h3>板子管理</h3>
          <p>维护封面、阵容配置、规则、小贴士与 FAQ，并自动生成阵容摘要。</p>
        </div>
        <el-button type="primary" @click="openCreate">新建板子</el-button>
      </div>
    </template>

    <el-table :data="boards" v-loading="loading">
      <el-table-column prop="name" label="板子名称" min-width="180" />
      <el-table-column prop="playerCount" label="人数" width="90" />
      <el-table-column prop="difficulty" label="难度" width="100" />
      <el-table-column label="标签" min-width="180">
        <template #default="{ row }">
          <div class="tag-row">
            <el-tag v-for="tag in row.tags" :key="tag" effect="plain" type="warning">{{ tag }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="briefConfig" label="阵容摘要" min-width="260" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button text type="danger" @click="removeBoard(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" width="1040px" :title="form.id ? '编辑板子' : '新建板子'">
    <div class="preview-card">
      <div class="preview-meta">Preview</div>
      <div class="preview-title">{{ form.name || '未命名板子' }}</div>
      <div class="preview-line">
        <span>{{ form.playerCount }} 人局</span>
        <span>{{ form.difficulty }}</span>
        <span>{{ form.ruleType || '标准板' }}</span>
      </div>
      <div class="preview-lineup">{{ lineupPreview || '请先配置角色阵容' }}</div>
      <div class="preview-brief">{{ briefConfigPreview || '自动生成的 brief_config 会显示在这里' }}</div>
      <div class="preview-desc">{{ cardDescriptionPreview }}</div>
      <div class="preview-count" :class="{ warning: configuredPlayers !== Number(form.playerCount) }">
        当前配置 {{ configuredPlayers }} 人 / 目标 {{ form.playerCount }} 人
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
          <div class="upload-stack">
            <el-input v-model="form.coverImage" placeholder="可直接填写图片 URL" />
            <el-upload :show-file-list="false" :http-request="uploadCover">
              <el-button>上传图片</el-button>
            </el-upload>
          </div>
        </el-form-item>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>角色配置</strong>
          <el-button text type="primary" @click="addRole">添加角色</el-button>
        </div>
        <div v-for="(item, index) in form.roles" :key="index" class="inline-row">
          <el-select v-model="item.roleId" placeholder="选择角色">
            <el-option v-for="role in roleOptions" :key="role.value" :label="role.label" :value="role.value" />
          </el-select>
          <el-input-number v-model="item.count" :min="1" :max="12" />
          <el-button text type="danger" @click="removeListItem(form.roles, index, { roleId: 0, count: 1 })">
            删除
          </el-button>
        </div>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>规则详情</strong>
          <el-button text type="primary" @click="addStringItem(form.specialRules)">新增规则</el-button>
        </div>
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
          <el-button
            text
            type="danger"
            @click="removeListItem(form.faqs, index, { question: '', answer: '' })"
          >
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
.panel-card {
  border-radius: 18px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.panel-kicker {
  color: #ffc000;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.panel-header h3 {
  margin: 8px 0 6px;
  font-size: 28px;
}

.panel-header p {
  margin: 0;
  color: #8d8d8d;
  line-height: 1.7;
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

.preview-brief {
  margin-top: 10px;
  color: #9f9f9f;
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

@media (max-width: 980px) {
  .form-grid,
  .faq-row,
  .inline-row {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: auto;
  }

  .upload-stack {
    flex-direction: column;
  }
}
</style>
