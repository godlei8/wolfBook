<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from './ui/PageHeader.vue'
import RoleEditor from './roles/RoleEditor.vue'
import { api } from '../services/api'
import type { Board, Role } from '../types'
import { CAMP_META, CAMP_ORDER, type CampKey, campOf, roleAccent } from '../utils/board'
import { resolveMediaUrl } from '../utils/media'

const emit = defineEmits<{ changed: [] }>()

const loading = ref(false)
const roles = ref<Role[]>([])
const boards = ref<Board[]>([])
const campFilter = ref<CampKey | 'ALL'>('ALL')
const onlyMissingArt = ref(false)
const keyword = ref('')
// 新建和编辑都在弹窗里：editingId 为 0 表示新建，null 表示没打开
const editingId = ref<number | null>(null)

const counts = computed(() => {
  const result: Record<CampKey, number> = { god: 0, villager: 0, wolf: 0, third: 0 }
  roles.value.forEach((role) => {
    result[campOf(role)] += 1
  })
  return result
})
const missingArt = computed(() => roles.value.filter((role) => !role.fullIllustration).length)
const editing = computed(() => (editingId.value ? roles.value.find((role) => role.id === editingId.value) || null : null))
const dialogOpen = computed({
  get: () => editingId.value !== null,
  set: (open: boolean) => {
    if (!open) editingId.value = null
  },
})

const filtered = computed(() => {
  const text = keyword.value.trim()
  return roles.value.filter((role) => {
    if (campFilter.value !== 'ALL' && campOf(role) !== campFilter.value) return false
    if (onlyMissingArt.value && role.fullIllustration) return false
    if (text && !`${role.name}${role.alias || ''}`.includes(text)) return false
    return true
  })
})

function cardImage(role: Role) {
  return resolveMediaUrl(role.fullIllustration)
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

async function onSaved() {
  editingId.value = null
  await load()
  emit('changed')
}

async function onRemoved() {
  editingId.value = null
  await load()
  emit('changed')
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="ROLES" title="角色图鉴">
    <template #description>
      {{ roles.length }} 个角色，{{ roles.length - missingArt }} 个有立绘。立绘、头像和主题色会用在图鉴、板子阵容和法官房。
    </template>
    <template #actions>
      <button class="nt-btn nt-btn--gold" type="button" @click="editingId = 0">
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
        :class="{ 'rc--on': editingId === role.id }"
        @click="editingId = role.id"
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

  </div>

  <el-dialog
    v-model="dialogOpen"
    :title="editingId ? `编辑「${editing?.name || ''}」` : '新建角色'"
    width="min(1000px, 94vw)"
    top="4vh"
    destroy-on-close
    append-to-body
  >
    <RoleEditor v-if="dialogOpen" :key="editingId ?? 'none'" :role="editing" :boards="boards" @saved="onSaved" @removed="onRemoved" @close="editingId = null" />
  </el-dialog>
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
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
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

</style>
