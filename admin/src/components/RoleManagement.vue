<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { api } from '../services/api'
import type { Role } from '../types'

const emit = defineEmits<{ changed: [] }>()

const roles = ref<Role[]>([])
const loading = ref(false)
const dialogVisible = ref(false)

const keyword = ref('')
const factionFilter = ref('ALL')
const roleTypeFilter = ref('ALL')
const currentPage = ref(1)
const pageSize = ref(8)

const factionOptions = ['好人', '狼人', '第三方']
const roleTypeOptionsMap: Record<string, string[]> = {
  好人: ['平民', '神职'],
  狼人: ['狼人', '功能狼'],
  第三方: ['第三方'],
}

const allRoleTypes = ['平民', '神职', '狼人', '功能狼', '第三方']

function buildCamp(faction: string, roleType: string) {
  if (!faction && !roleType) return ''
  if (!roleType) return faction
  return `${faction}·${roleType}`
}

function splitCamp(camp = '') {
  if (!camp) {
    return { faction: '好人', roleType: '神职' }
  }
  const normalized = camp.replace('/', '·').replace('-', '·')
  if (normalized.includes('·')) {
    const [faction, roleType] = normalized.split('·', 2)
    return { faction, roleType }
  }
  if (normalized.startsWith('狼人')) {
    return { faction: '狼人', roleType: '狼人' }
  }
  if (normalized.startsWith('第三方')) {
    return { faction: '第三方', roleType: '第三方' }
  }
  return { faction: '好人', roleType: '神职' }
}

const emptyRole = (): Role => ({
  id: 0,
  name: '',
  alias: '',
  faction: '好人',
  roleType: '神职',
  camp: '好人·神职',
  skill: '',
  background: '',
  faqs: [{ question: '', answer: '' }],
  portrait: '',
  fullIllustration: '',
})

const form = reactive<Role>(emptyRole())

const availableRoleTypes = computed(() => roleTypeOptionsMap[form.faction] || [])
const campPreview = computed(() => buildCamp(form.faction, form.roleType))

const filteredRoles = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return roles.value.filter((role) => {
    const searchableText = [role.name, role.alias || '', role.camp, role.faction, role.roleType]
      .join(' ')
      .toLowerCase()
    const matchesKeyword = !normalizedKeyword || searchableText.includes(normalizedKeyword)
    const matchesFaction = factionFilter.value === 'ALL' || role.faction === factionFilter.value
    const matchesType = roleTypeFilter.value === 'ALL' || role.roleType === roleTypeFilter.value
    return matchesKeyword && matchesFaction && matchesType
  })
})

const pagedRoles = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRoles.value.slice(start, start + pageSize.value)
})

watch([keyword, factionFilter, roleTypeFilter, pageSize], () => {
  currentPage.value = 1
})

function syncCamp() {
  form.camp = buildCamp(form.faction, form.roleType)
}

function ensureRoleType() {
  if (!availableRoleTypes.value.includes(form.roleType)) {
    form.roleType = availableRoleTypes.value[0] || ''
  }
  syncCamp()
}

function normalizeRole(role?: Role): Role {
  if (!role) {
    return emptyRole()
  }
  const next = JSON.parse(JSON.stringify(role)) as Role
  const fallback = splitCamp(next.camp)
  next.faction = next.faction || fallback.faction
  next.roleType = next.roleType || fallback.roleType
  next.camp = buildCamp(next.faction, next.roleType)
  next.alias = next.alias || ''
  next.skill = next.skill || ''
  next.background = next.background || ''
  next.portrait = next.portrait || ''
  next.fullIllustration = next.fullIllustration || ''
  next.faqs = next.faqs?.length ? next.faqs : [{ question: '', answer: '' }]
  return next
}

function resetForm(role?: Role) {
  Object.assign(form, normalizeRole(role))
  ensureRoleType()
}

function resetFilters() {
  keyword.value = ''
  factionFilter.value = 'ALL'
  roleTypeFilter.value = 'ALL'
  currentPage.value = 1
}

function addFaq() {
  form.faqs.push({ question: '', answer: '' })
}

function removeFaq(index: number) {
  if (form.faqs.length === 1) {
    form.faqs.splice(0, 1, { question: '', answer: '' })
    return
  }
  form.faqs.splice(index, 1)
}

async function load() {
  loading.value = true
  try {
    roles.value = await api.getRoles()
    if ((currentPage.value - 1) * pageSize.value >= roles.value.length) {
      currentPage.value = 1
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色数据加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  ensureRoleType()
  const payload = {
    name: form.name,
    alias: form.alias || '',
    faction: form.faction,
    roleType: form.roleType,
    skill: form.skill,
    background: form.background,
    faqs: form.faqs.filter((faq) => faq.question && faq.answer),
    portrait: form.portrait,
    fullIllustration: form.fullIllustration,
  }

  try {
    if (form.id) {
      await api.updateRole(form.id, payload)
      ElMessage.success('角色已更新')
    } else {
      await api.createRole(payload)
      ElMessage.success('角色已创建')
    }
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色保存失败')
  }
}

async function removeRole(id: number) {
  try {
    await api.deleteRole(id)
    ElMessage.success('角色已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色删除失败')
  }
}

async function uploadTo(target: 'portrait' | 'fullIllustration', options: UploadRequestOptions) {
  try {
    const result = await api.upload(options.file as File)
    form[target] = result.url
    options.onSuccess?.(result)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '图片上传失败')
    options.onError?.(Object.assign(new Error('upload failed'), { status: 0, method: 'post', url: '' }) as never)
  }
}

function uploadPortrait(options: UploadRequestOptions) {
  return uploadTo('portrait', options)
}

function uploadIllustration(options: UploadRequestOptions) {
  return uploadTo('fullIllustration', options)
}

onMounted(load)
</script>

<template>
  <el-card class="panel-card">
    <template #header>
      <div class="panel-header">
        <div class="panel-copy">
          <div class="panel-kicker">Roles</div>
          <h3>角色管理</h3>
          <p>角色类型固定为平民 / 神职 / 狼人 / 功能狼 / 第三方，避免后台录入发散。</p>
        </div>
        <el-button type="primary" @click="resetForm(); dialogVisible = true">新建角色</el-button>
      </div>
    </template>

    <div class="table-toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索角色名称、别名或组合标签" />
      <el-select v-model="factionFilter">
        <el-option label="全部阵营" value="ALL" />
        <el-option v-for="item in factionOptions" :key="item" :label="item" :value="item" />
      </el-select>
      <el-select v-model="roleTypeFilter">
        <el-option label="全部类型" value="ALL" />
        <el-option v-for="item in allRoleTypes" :key="item" :label="item" :value="item" />
      </el-select>
      <el-button @click="resetFilters">重置</el-button>
      <div class="toolbar-summary">当前 {{ filteredRoles.length }} 条</div>
    </div>

    <el-table :data="pagedRoles" v-loading="loading" empty-text="暂无符合条件的角色">
      <el-table-column prop="name" label="角色名称" min-width="150" />
      <el-table-column prop="faction" label="阵营" width="110">
        <template #default="{ row }">
          <el-tag :type="row.faction === '狼人' ? 'danger' : row.faction === '第三方' ? 'info' : 'warning'">
            {{ row.faction }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="roleType" label="角色类型" width="120" />
      <el-table-column prop="camp" label="组合标签" min-width="140" />
      <el-table-column prop="alias" label="别名" min-width="120" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="resetForm(row); dialogVisible = true">编辑</el-button>
          <el-button text type="danger" @click="removeRole(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <div class="table-total">共 {{ filteredRoles.length }} 条角色</div>
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        background
        size="small"
        layout="sizes, prev, pager, next"
        :pager-count="5"
        :page-sizes="[8, 10, 20, 30]"
        :total="filteredRoles.length"
      />
    </div>
  </el-card>

  <el-dialog v-model="dialogVisible" width="880px" :title="form.id ? '编辑角色' : '新建角色'">
    <div class="preview-card">
      <div class="preview-meta">Role Preview</div>
      <div class="preview-title">{{ form.name || '未命名角色' }}</div>
      <div class="preview-pills">
        <el-tag effect="dark" type="warning">{{ form.faction }}</el-tag>
        <el-tag effect="plain">{{ form.roleType }}</el-tag>
        <el-tag effect="plain">{{ campPreview }}</el-tag>
      </div>
    </div>

    <el-form label-position="top">
      <div class="form-grid form-grid-role">
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input v-model="form.alias" />
        </el-form-item>
        <el-form-item label="阵营">
          <el-select v-model="form.faction" @change="ensureRoleType">
            <el-option v-for="item in factionOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色类型">
          <el-select v-model="form.roleType" @change="syncCamp">
            <el-option v-for="item in availableRoleTypes" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
      </div>

      <el-form-item label="组合标签">
        <div class="camp-preview">{{ campPreview }}</div>
      </el-form-item>

      <el-form-item label="技能说明">
        <el-input v-model="form.skill" type="textarea" :rows="3" />
      </el-form-item>
      <el-form-item label="背景故事">
        <el-input v-model="form.background" type="textarea" :rows="3" />
      </el-form-item>

      <div class="form-grid">
        <el-form-item label="头像">
          <div class="upload-stack">
            <el-input v-model="form.portrait" />
            <el-upload :show-file-list="false" :http-request="uploadPortrait">
              <el-button>上传</el-button>
            </el-upload>
          </div>
        </el-form-item>
        <el-form-item label="全身立绘">
          <div class="upload-stack">
            <el-input v-model="form.fullIllustration" />
            <el-upload :show-file-list="false" :http-request="uploadIllustration">
              <el-button>上传</el-button>
            </el-upload>
          </div>
        </el-form-item>
      </div>

      <div class="array-section">
        <div class="array-header">
          <strong>FAQ</strong>
          <el-button text type="primary" @click="addFaq">新增 FAQ</el-button>
        </div>
        <div v-for="(faq, index) in form.faqs" :key="index" class="faq-row">
          <el-input v-model="faq.question" placeholder="问题" />
          <el-input v-model="faq.answer" type="textarea" :rows="2" placeholder="答案" />
          <el-button text type="danger" @click="removeFaq(index)">删除</el-button>
        </div>
      </div>
    </el-form>

    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="submit">保存角色</el-button>
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
  align-items: flex-start;
}

.panel-copy {
  display: grid;
  gap: 4px;
}

.panel-kicker {
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.panel-header h3 {
  margin: 0;
  font-size: 22px;
  line-height: 1.1;
}

.panel-header p {
  margin: 0;
  color: #8d8d8d;
  line-height: 1.55;
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

.toolbar-summary {
  margin-left: auto;
  flex: 0 0 auto;
  color: #8d8d8d;
  font-size: 13px;
  white-space: nowrap;
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

.preview-pills {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.form-grid-role {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.camp-preview {
  min-height: 44px;
  display: flex;
  align-items: center;
  padding: 0 14px;
  border-radius: 10px;
  border: 1px solid rgba(255, 192, 0, 0.18);
  background: rgba(255, 192, 0, 0.08);
  color: #f5d069;
  font-weight: 600;
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

  .toolbar-summary {
    margin-left: 0;
  }

  .table-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 980px) {
  .panel-header {
    display: grid;
  }

  .form-grid,
  .form-grid-role,
  .faq-row {
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
  .toolbar-summary {
    flex: initial;
    min-width: 0;
    margin-left: 0;
  }

  .upload-stack {
    flex-direction: column;
  }
}
</style>
