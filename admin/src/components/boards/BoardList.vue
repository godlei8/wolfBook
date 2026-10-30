<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../ui/PageHeader.vue'
import RoleToken from '../ui/RoleToken.vue'
import CampBar from '../ui/CampBar.vue'
import { api } from '../../services/api'
import type { Board, Role } from '../../types'
import { campSegments, featuredBoards, lineupSize, pickLineupArt, roleImage, sortLineup, JUDGE_SUPPORT_META, judgeSupport } from '../../utils/board'
import { resolveMediaUrl } from '../../utils/media'
import { clampPage, paginate } from '../../utils/pagination'
import { go } from '../../utils/route'

const emit = defineEmits<{ changed: [] }>()

const PAGE_SIZE = 9

const loading = ref(false)
const boards = ref<Board[]>([])
const roles = ref<Role[]>([])
const keyword = ref('')
const playerFilter = ref<number | 'ALL'>('ALL')
const difficultyFilter = ref('ALL')
const gapFilter = ref<'ALL' | 'online' | 'offline' | 'noBackground' | 'mismatch'>('ALL')
const page = ref(1)
const busyId = ref<number | null>(null)

const rolesById = computed(() => new Map(roles.value.map((role) => [role.id, role])))
const playerOptions = computed(() => [...new Set(boards.value.map((board) => board.playerCount))].sort((a, b) => a - b))
const featuredIds = computed(() => featuredBoards(boards.value).map((board) => board.id))

function mismatch(board: Board) {
  return lineupSize(board.roles) !== board.playerCount
}

const stats = computed(() => ({
  all: boards.value.length,
  online: boards.value.filter((board) => board.status === 1).length,
  offline: boards.value.filter((board) => board.status !== 1).length,
  noBackground: boards.value.filter((board) => !board.backgroundImage).length,
  mismatch: boards.value.filter(mismatch).length,
}))

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return boards.value.filter((board) => {
    const roleNames = board.roles.map((item) => rolesById.value.get(item.roleId)?.name || '').join(' ')
    const haystack = [board.name, board.tags.join(' '), board.cardDescription, roleNames].join(' ').toLowerCase()
    if (text && !haystack.includes(text)) return false
    if (playerFilter.value !== 'ALL' && board.playerCount !== playerFilter.value) return false
    if (difficultyFilter.value !== 'ALL' && board.difficulty !== difficultyFilter.value) return false
    if (gapFilter.value === 'online' && board.status !== 1) return false
    if (gapFilter.value === 'offline' && board.status === 1) return false
    if (gapFilter.value === 'noBackground' && board.backgroundImage) return false
    if (gapFilter.value === 'mismatch' && !mismatch(board)) return false
    return true
  })
})

const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE)))

const cards = computed(() =>
  paginate(filtered.value, page.value, PAGE_SIZE).map((board) => {
    const lineup = sortLineup(board.roles, rolesById.value)
    return {
      board,
      background: resolveMediaUrl(board.backgroundImage),
      fan: board.backgroundImage ? [] : fanCards(board),
      segments: campSegments(board.roles, rolesById.value),
      support: judgeSupport(board.roles, rolesById.value),
      lineup: lineup.map((item) => ({ role: rolesById.value.get(item.roleId), count: item.count, key: item.roleId })),
      size: lineupSize(board.roles),
      featuredAt: featuredIds.value.indexOf(board.id) + 1,
    }
  }),
)

// 没背景图时摆三张阵容立绘，立绘不够的位置用卡背
function fanCards(board: Board) {
  const picked = pickLineupArt(board.roles, rolesById.value).map((item) => resolveMediaUrl(roleImage(item.role)))
  while (picked.length < 3) picked.push('')
  return picked.slice(0, 3)
}

watch([keyword, playerFilter, difficultyFilter, gapFilter], () => {
  page.value = 1
})

watch(
  () => filtered.value.length,
  (total) => {
    page.value = clampPage(page.value, PAGE_SIZE, total)
  },
)

function toggleGap(key: typeof gapFilter.value) {
  gapFilter.value = gapFilter.value === key ? 'ALL' : key
}

async function load() {
  loading.value = true
  try {
    const [boardData, roleData] = await Promise.all([api.getBoards(), api.getRoles()])
    boards.value = boardData
    roles.value = roleData
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子加载失败')
  } finally {
    loading.value = false
  }
}

async function toggleStatus(board: Board) {
  const online = board.status !== 1
  if (!online) {
    try {
      await ElMessageBox.confirm(`下架后小程序里看不到「${board.name}」，也不能用它新建法官房间；已经开局的房间不受影响。`, '下架板子', {
        type: 'warning',
        confirmButtonText: '下架',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  busyId.value = board.id
  try {
    const updated = await api.updateBoardStatus(board.id, online)
    board.status = updated.status
    ElMessage.success(online ? `「${board.name}」已上架` : `「${board.name}」已下架`)
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  } finally {
    busyId.value = null
  }
}

async function removeBoard(board: Board) {
  try {
    await ElMessageBox.confirm(`删除「${board.name}」和它的阵容配置，引用它的法官房间将无法开局。删除后不能恢复，只想先藏起来请用下架。`, '删除板子', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api.deleteBoard(board.id)
    ElMessage.success('板子已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '板子删除失败')
  }
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="BOARDS" title="板子" description="配阵容、写规则、挂背景图。这里改完，小程序马上生效。">
    <template #actions>
      <label class="search">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
        <span class="nt-sr-only">搜索板子</span>
        <input v-model="keyword" type="search" placeholder="搜板子名、角色、标签" />
      </label>
      <button class="nt-btn nt-btn--gold" type="button" @click="go('boards/new')">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
        新建板子
      </button>
    </template>
  </PageHeader>

  <section class="stats" aria-label="板子概况">
    <button type="button" class="stat" :class="{ 'stat--on': gapFilter === 'ALL' }" @click="gapFilter = 'ALL'">
      <span>全部板子</span><strong>{{ stats.all }}</strong>
    </button>
    <button type="button" class="stat" :class="{ 'stat--on': gapFilter === 'online' }" @click="toggleGap('online')">
      <span>已上架</span><strong class="c-moon">{{ stats.online }}</strong>
    </button>
    <button type="button" class="stat" :class="{ 'stat--on': gapFilter === 'offline' }" @click="toggleGap('offline')">
      <span>已下架</span><strong class="c-soft">{{ stats.offline }}</strong>
    </button>
    <button type="button" class="stat stat--warn" :class="{ 'stat--on': gapFilter === 'noBackground' }" @click="toggleGap('noBackground')">
      <span>缺背景图</span><strong class="c-gold">{{ stats.noBackground }}</strong>
    </button>
    <button type="button" class="stat" :class="{ 'stat--on': gapFilter === 'mismatch', 'stat--danger': stats.mismatch }" @click="toggleGap('mismatch')">
      <span>阵容人数不符</span><strong :class="stats.mismatch ? 'c-wolf' : 'c-ok'">{{ stats.mismatch }}</strong>
    </button>
  </section>

  <section class="filters" aria-label="筛选">
    <span class="filters__label">人数</span>
    <button type="button" class="nt-chip" :class="{ 'nt-chip--on': playerFilter === 'ALL' }" @click="playerFilter = 'ALL'">全部</button>
    <button v-for="count in playerOptions" :key="count" type="button" class="nt-chip" :class="{ 'nt-chip--on': playerFilter === count }" @click="playerFilter = count">{{ count }} 人</button>
    <span class="nt-divider"></span>
    <span class="filters__label">难度</span>
    <button v-for="level in ['ALL', '入门', '进阶', '烧脑']" :key="level" type="button" class="nt-chip" :class="{ 'nt-chip--on': difficultyFilter === level }" @click="difficultyFilter = level">{{ level === 'ALL' ? '全部' : level }}</button>
    <span class="filters__note">首页推荐按「有背景图优先」取前 5 个</span>
  </section>

  <div v-if="loading && !boards.length" class="nt-empty">正在翻板子…</div>
  <div v-else-if="!cards.length" class="nt-empty">没有符合条件的板子</div>

  <section v-else class="grid" aria-label="板子列表">
    <article v-for="card in cards" :key="card.board.id" class="card" :class="{ 'card--off': card.board.status !== 1 }">
      <div class="card__top">
        <img v-if="card.background" class="card__bg" :src="card.background" alt="" loading="lazy" />
        <div v-else class="card__fan">
          <span v-for="(image, index) in card.fan" :key="index" class="card__fan-card" :class="[`card__fan-card--${index}`, { 'nt-cardback': !image }]">
            <img v-if="image" :src="image" alt="" loading="lazy" />
          </span>
        </div>
        <div class="card__shade"></div>
        <div class="card__badges">
          <span class="nt-badge" :class="card.board.status === 1 ? 'nt-badge--on' : 'nt-badge--off'">{{ card.board.status === 1 ? '上架中' : '已下架' }}</span>
          <span v-if="card.featuredAt" class="pill pill--gold">首页推荐 #{{ card.featuredAt }}</span>
          <span v-if="!card.background" class="pill">未配背景图</span>
        </div>
      </div>

      <div class="card__body">
        <div class="card__title">
          <h2>{{ card.board.name }}</h2>
          <span>#{{ card.board.id }}</span>
        </div>
        <div class="card__meta">
          <span>{{ card.board.playerCount }} 人</span><i>/</i><span>{{ card.board.difficulty }}</span><i>/</i><span>{{ card.board.winCondition || '屠边' }}</span>
          <template v-if="card.board.tags.length"><i>/</i><span>{{ card.board.tags.join(' · ') }}</span></template>
        </div>
        <CampBar :segments="card.segments" />
        <p v-if="card.size !== card.board.playerCount" class="card__warn">阵容 {{ card.size }} 人，和玩家人数 {{ card.board.playerCount }} 不一致</p>
        <p class="card__judge" :class="`card__judge--${JUDGE_SUPPORT_META[card.support.level].tone}`">
          电子法官：{{ JUDGE_SUPPORT_META[card.support.level].label }}<template v-if="card.support.missing.length">（{{ card.support.missing.join('、') }} 没有机制）</template>
        </p>
        <div class="card__lineup">
          <RoleToken v-for="item in card.lineup" :key="item.key" :role="item.role" :count="item.count" ring />
        </div>
        <div class="card__actions">
          <a class="nt-btn nt-btn--gold nt-btn--small" :href="`#/boards/${card.board.id}`">编辑</a>
          <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="busyId === card.board.id" @click="toggleStatus(card.board)">
            {{ card.board.status === 1 ? '下架' : '上架' }}
          </button>
          <button class="nt-btn nt-btn--ghost nt-btn--small card__del" type="button" :aria-label="`删除${card.board.name}`" title="删除" @click="removeBoard(card.board)">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
          </button>
        </div>
      </div>
    </article>
  </section>

  <footer v-if="filtered.length" class="pager">
    <span>共 {{ filtered.length }} 个板子，显示 {{ (page - 1) * PAGE_SIZE + 1 }}–{{ Math.min(page * PAGE_SIZE, filtered.length) }}</span>
    <div v-if="pageCount > 1" class="pager__btns">
      <button v-for="n in pageCount" :key="n" type="button" class="nt-chip" :class="{ 'nt-chip--on': n === page }" :aria-current="n === page ? 'page' : undefined" @click="page = n">{{ n }}</button>
    </div>
  </footer>
</template>

<style scoped>
.search {
  width: 280px;
  max-width: 100%;
  height: 44px;
  padding: 0 16px;
  border-radius: 14px;
  background: var(--panel);
  border: 1px solid var(--line);
  color: var(--text-3);
  display: flex;
  align-items: center;
  gap: 10px;
}

.search:focus-within {
  border-color: var(--moon);
}

.search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font-size: 15px;
}

.search input::placeholder {
  color: var(--text-3);
}

.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 14px;
}

.stat {
  padding: 16px 18px;
  border-radius: 18px;
  background: var(--panel);
  border: 1px solid var(--panel-line);
  color: var(--text-3);
  font-size: 13px;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 6px;
  cursor: pointer;
}

.stat:hover {
  border-color: var(--text-3);
}

.stat strong {
  font: 900 30px var(--serif);
  color: var(--text);
}

.stat--warn {
  background: var(--warn-bg);
  border-color: var(--warn-line);
  color: var(--camp-god);
}

.stat--danger {
  background: var(--danger-bg);
}

.stat--on {
  border: 2px solid var(--moon);
  padding: 15px 17px;
}

.c-moon { color: var(--moon) !important; }
.c-soft { color: var(--text-2) !important; }
.c-gold { color: var(--gold) !important; }
.c-wolf { color: var(--camp-wolf) !important; }
.c-ok { color: var(--camp-villager) !important; }

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.filters__label {
  font-size: 13px;
  color: var(--text-3);
}

.filters__note {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-3);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 24px;
}

.card {
  background: var(--panel);
  border: 2px solid var(--ink);
  border-radius: 22px;
  box-shadow: 5px 5px 0 var(--ink);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.card--off .card__top {
  filter: grayscale(0.7) brightness(0.75);
}

.card__top {
  position: relative;
  height: 168px;
  overflow: hidden;
  background: #1b1724;
}

.card__bg {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card__fan {
  position: absolute;
  inset: 18px 0 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.card__fan-card {
  width: 92px;
  height: 124px;
  border-radius: 12px;
  border: 2px solid var(--ink);
  box-shadow: 3px 3px 0 var(--ink);
  overflow: hidden;
}

.card__fan-card img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 50% 12%;
}

.card__fan-card--0 {
  transform: rotate(-8deg) translateX(14px);
}

.card__fan-card--1 {
  z-index: 2;
  width: 104px;
  height: 140px;
  transform: translateY(-6px);
}

.card__fan-card--2 {
  transform: rotate(8deg) translateX(-14px);
}

.card__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(14, 12, 19, 0) 40%, rgba(33, 28, 43, 0.95) 100%);
}

.card__badges {
  position: absolute;
  z-index: 3;
  top: 12px;
  left: 12px;
  right: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.pill {
  height: 26px;
  padding: 0 10px;
  border-radius: 13px;
  background: rgba(14, 12, 19, 0.82);
  color: var(--gold);
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
}

.pill--gold {
  background: var(--gold);
  color: var(--ink);
  border: 2px solid var(--ink);
  font-weight: 900;
}

.card__body {
  padding: 4px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
}

.card__title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.card__title h2 {
  margin: 0;
  font: 900 22px var(--serif);
}

.card__title span {
  font-size: 13px;
  color: var(--text-3);
}

.card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: var(--text-2);
}

.card__meta i {
  font-style: normal;
  color: var(--line);
}

.card__judge {
  margin: 0;
  font-size: 12px;
}

.card__judge--ok {
  color: var(--moon);
}

.card__judge--warn {
  color: var(--gold);
}

.card__judge--off {
  color: var(--text-3);
}

.card__warn {
  margin: 0;
  padding: 8px 12px;
  border-radius: 12px;
  background: var(--danger-bg);
  color: #ffb1a9;
  font-size: 13px;
}

.card__lineup {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  padding-bottom: 2px;
}

.card__actions {
  margin-top: auto;
  display: flex;
  gap: 10px;
  padding-top: 4px;
}

.card__actions > :not(.card__del) {
  flex: 1;
}

.card__del {
  width: 36px;
  padding: 0;
}

.pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-3);
  font-size: 14px;
}

.pager__btns {
  display: flex;
  gap: 8px;
}
</style>
