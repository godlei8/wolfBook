<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from './ui/PageHeader.vue'
import ImageField from './ui/ImageField.vue'
import RoleToken from './ui/RoleToken.vue'
import { api } from '../services/api'
import type { Role, WorldCamp, WorldLore } from '../types'

// 世界观（夜镇编年史）：小程序图鉴页顶部的入口点进去就是这里的内容，整份一起保存
const MAX_FACTIONS = 6
const MAX_EVENTS = 30
const MAX_EVENT_ROLES = 10
const CAMPS: { value: WorldCamp; label: string; color: string }[] = [
  { value: 'good', label: '好人', color: '#8fd0a6' },
  { value: 'wolf', label: '狼人', color: '#ff7468' },
  { value: 'third', label: '第三方', color: '#f7a8cb' },
]

const loading = ref(true)
const saving = ref(false)
const roles = ref<Role[]>([])
let snapshot = ''

const form = reactive({
  townName: '',
  tagline: '',
  intro: '',
  coverImage: '',
  factions: [] as { name: string; camp: WorldCamp; description: string }[],
  events: [] as { era: string; title: string; content: string; roleIds: number[] }[],
})

const rolesById = computed(() => new Map(roles.value.map((role) => [role.id, role])))
const dirty = computed(() => !loading.value && JSON.stringify(form) !== snapshot)

function fill(lore: WorldLore) {
  form.townName = lore.townName || ''
  form.tagline = lore.tagline || ''
  form.intro = lore.intro || ''
  form.coverImage = lore.coverImage || ''
  form.factions = (lore.factions || []).map((faction) => ({ name: faction.name || '', camp: faction.camp || 'good', description: faction.description || '' }))
  form.events = (lore.events || []).map((event) => ({ era: event.era || '', title: event.title || '', content: event.content || '', roleIds: [...(event.roleIds || [])] }))
  snapshot = JSON.stringify(form)
}

async function load() {
  loading.value = true
  try {
    const [lore, roleData] = await Promise.all([api.getWorld(), api.getRoles()])
    roles.value = roleData
    fill(lore)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '世界观加载失败')
  } finally {
    loading.value = false
  }
}

function addFaction() {
  form.factions.push({ name: '', camp: 'good', description: '' })
}

function addEvent() {
  form.events.push({ era: '', title: '', content: '', roleIds: [] })
}

function moveEvent(from: number, to: number) {
  if (to < 0 || to >= form.events.length) return
  const [event] = form.events.splice(from, 1)
  form.events.splice(to, 0, event)
}

async function removeEvent(index: number) {
  const event = form.events[index]
  if (event.title || event.content) {
    try {
      await ElMessageBox.confirm(`删掉「${event.title || '这一段'}」？保存后才会生效。`, '删除事件', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  form.events.splice(index, 1)
}

function addEventRole(index: number, event: Event) {
  const select = event.target as HTMLSelectElement
  const roleId = Number(select.value)
  const list = form.events[index].roleIds
  if (roleId && !list.includes(roleId) && list.length < MAX_EVENT_ROLES) list.push(roleId)
  select.value = ''
}

async function save() {
  const missingTitle = form.events.findIndex((event) => !event.title.trim() && (event.content.trim() || event.era.trim()))
  if (missingTitle >= 0) {
    ElMessage.warning(`第 ${missingTitle + 1} 个事件没写标题`)
    return
  }
  const missingName = form.factions.findIndex((faction) => !faction.name.trim() && faction.description.trim())
  if (missingName >= 0) {
    ElMessage.warning(`第 ${missingName + 1} 个势力没写名称`)
    return
  }
  saving.value = true
  try {
    const saved = await api.saveWorld({
      townName: form.townName,
      tagline: form.tagline,
      intro: form.intro,
      coverImage: form.coverImage || null,
      factions: form.factions,
      events: form.events,
    })
    fill(saved)
    ElMessage.success('世界观已保存，小程序马上生效')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '世界观保存失败')
  } finally {
    saving.value = false
  }
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault()
}

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void load()
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <PageHeader eyebrow="WORLD" title="世界观" description="夜镇编年史：小程序图鉴页顶部的入口，点进去就是这一页的内容。">
    <template #actions>
      <span v-if="dirty" class="dirty">有改动还没保存</span>
      <button class="nt-btn nt-btn--gold" type="button" :disabled="saving || loading" @click="save">{{ saving ? '保存中…' : '保存世界观' }}</button>
    </template>
  </PageHeader>

  <p v-if="loading" class="nt-empty">正在翻编年史…</p>

  <template v-else>
    <section class="nt-panel">
      <h2 class="nt-h2"><span class="nt-num">1</span>小镇</h2>
      <div class="town">
        <div class="town__fields">
          <div class="grid2">
            <div class="nt-field">
              <label class="nt-label" for="w-town">小镇名称</label>
              <input id="w-town" v-model="form.townName" class="nt-input" maxlength="30" placeholder="灰铃镇" />
            </div>
            <div class="nt-field">
              <label class="nt-label" for="w-tagline">一句话 <small>· 入口卡片上的那句</small></label>
              <input id="w-tagline" v-model="form.tagline" class="nt-input" maxlength="60" placeholder="银钟响过，不出家门。" />
            </div>
          </div>
          <div class="nt-field">
            <label class="nt-label" for="w-intro">小镇简介 <small>· 换行分段，{{ form.intro.length }} / 3000</small></label>
            <textarea id="w-intro" v-model="form.intro" class="nt-input" rows="7" maxlength="3000"></textarea>
          </div>
        </div>
        <ImageField v-model="form.coverImage" class="town__cover" name="coverImage" label="封面图" hint="横版，可不传，默认用月夜钟楼" />
      </div>
    </section>

    <section class="nt-panel">
      <div class="nt-row-between">
        <h2 class="nt-h2"><span class="nt-num">2</span>三方势力</h2>
        <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="form.factions.length >= MAX_FACTIONS" @click="addFaction">+ 加一个势力</button>
      </div>
      <div class="factions">
        <div v-for="(faction, index) in form.factions" :key="index" class="faction" :style="{ borderTopColor: CAMPS.find((camp) => camp.value === faction.camp)?.color }">
          <div class="faction__head">
            <input v-model="faction.name" class="nt-input faction__name" maxlength="30" :aria-label="`第 ${index + 1} 个势力的名称`" placeholder="势力名称" />
            <button type="button" class="nt-iconbtn" :aria-label="`删除第 ${index + 1} 个势力`" title="删除" @click="form.factions.splice(index, 1)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
            </button>
          </div>
          <div class="nt-seg" role="radiogroup" :aria-label="`第 ${index + 1} 个势力的阵营`">
            <button v-for="camp in CAMPS" :key="camp.value" type="button" role="radio" :aria-checked="faction.camp === camp.value" :class="{ on: faction.camp === camp.value }" @click="faction.camp = camp.value">{{ camp.label }}</button>
          </div>
          <textarea v-model="faction.description" class="nt-input" rows="5" maxlength="1000" :aria-label="`第 ${index + 1} 个势力的介绍`" placeholder="这一方是谁、想要什么"></textarea>
        </div>
      </div>
      <p v-if="!form.factions.length" class="nt-empty">还没有势力</p>
    </section>

    <section class="nt-panel">
      <div class="nt-row-between">
        <h2 class="nt-h2"><span class="nt-num">3</span>编年史 <small class="count">{{ form.events.length }} 段</small></h2>
        <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="form.events.length >= MAX_EVENTS" @click="addEvent">+ 加一段</button>
      </div>
      <p class="nt-muted">按时间从早到晚排，小程序里是一条时间线；每段下面的角色头像点了能进角色详情。</p>
      <ol class="timeline">
        <li v-for="(event, index) in form.events" :key="index" class="event">
          <span class="event__dot" aria-hidden="true"></span>
          <div class="event__card">
            <div class="event__head">
              <input v-model="event.era" class="nt-input event__era" maxlength="30" :aria-label="`第 ${index + 1} 段的时间`" placeholder="时间，比如：三百年前" />
              <input v-model="event.title" class="nt-input event__title" maxlength="40" :aria-label="`第 ${index + 1} 段的标题`" placeholder="标题" />
              <span class="event__ops">
                <button type="button" class="nt-iconbtn" aria-label="上移" :disabled="index === 0" @click="moveEvent(index, index - 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 15 6-6 6 6" /></svg></button>
                <button type="button" class="nt-iconbtn" aria-label="下移" :disabled="index === form.events.length - 1" @click="moveEvent(index, index + 1)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg></button>
                <button type="button" class="nt-iconbtn" aria-label="删除这一段" @click="removeEvent(index)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
              </span>
            </div>
            <textarea v-model="event.content" class="nt-input" rows="3" maxlength="3000" :aria-label="`第 ${index + 1} 段的正文`" placeholder="发生了什么"></textarea>
            <div class="event__roles">
              <span class="nt-label">出场角色</span>
              <button v-for="(roleId, roleIndex) in event.roleIds" :key="roleId" type="button" class="cast" :aria-label="`移除 ${rolesById.get(roleId)?.name}`" title="点一下移除" @click="event.roleIds.splice(roleIndex, 1)">
                <RoleToken :role="rolesById.get(roleId)" :size="28" />
                {{ rolesById.get(roleId)?.name || `#${roleId}` }}
                <span aria-hidden="true">×</span>
              </button>
              <select class="nt-input cast-add" :aria-label="`给第 ${index + 1} 段加出场角色`" :disabled="event.roleIds.length >= MAX_EVENT_ROLES" @change="addEventRole(index, $event)">
                <option value="">+ 角色</option>
                <option v-for="role in roles.filter((item) => !event.roleIds.includes(item.id))" :key="role.id" :value="role.id">{{ role.name }}{{ role.alias ? ` · ${role.alias}` : '' }}</option>
              </select>
            </div>
          </div>
        </li>
      </ol>
      <p v-if="!form.events.length" class="nt-empty">还没有编年史，点「加一段」</p>
    </section>

    <div class="savebar">
      <p>{{ dirty ? '有改动还没保存' : '内容和小程序一致' }}</p>
      <button class="nt-btn nt-btn--gold" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存世界观' }}</button>
    </div>
  </template>
</template>

<style scoped>
.dirty {
  font-size: 14px;
  color: var(--gold);
}

.town {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 22px;
  align-items: start;
}

.town__fields {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.grid2 {
  display: grid;
  grid-template-columns: minmax(0, 0.7fr) minmax(0, 1.3fr);
  gap: 16px;
}

.factions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.faction {
  padding: 16px;
  border-radius: 16px;
  background: var(--panel-raised);
  border-top: 4px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.faction__head {
  display: flex;
  gap: 10px;
  align-items: center;
}

.faction__name {
  font: 900 17px var(--serif);
}

.count {
  font: 600 13px var(--sans);
  color: var(--text-3);
}

.timeline {
  list-style: none;
  margin: 0;
  padding: 0 0 0 22px;
  border-left: 2px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.event {
  position: relative;
}

.event__dot {
  position: absolute;
  left: -31px;
  top: 18px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--gold);
  border: 3px solid var(--ink);
}

.event__card {
  padding: 14px;
  border-radius: 16px;
  background: var(--panel-raised);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.event__head {
  display: grid;
  grid-template-columns: 170px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.event__era {
  color: var(--gold);
  font-weight: 700;
}

.event__title {
  font: 900 16px var(--serif);
}

.event__ops {
  display: flex;
  gap: 6px;
}

.event__roles {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.cast {
  height: 36px;
  padding: 0 10px 0 4px;
  border-radius: 18px;
  border: 1px solid var(--line);
  background: var(--panel);
  color: var(--text);
  font: 600 13px var(--sans);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.cast:hover {
  border-color: var(--camp-wolf);
}

.cast span {
  color: var(--text-3);
}

.cast-add {
  width: auto;
  min-width: 120px;
  height: 36px;
  font-size: 13px;
  border-radius: 18px;
}

.savebar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 16px 20px;
  border-radius: 18px;
  background: var(--bar-bg);
  border: 2px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
}

.savebar p {
  margin: 0;
  font-size: 14px;
  color: var(--text-2);
}

@media (max-width: 900px) {
  .town,
  .grid2,
  .event__head {
    grid-template-columns: 1fr;
  }
}
</style>
