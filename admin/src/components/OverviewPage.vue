<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PageHeader from './ui/PageHeader.vue'
import { api } from '../services/api'
import type { Board, DashboardSummary, ReportItem, Role, WorldEvent } from '../types'
import { featuredBoards, formatTime, pickLineupArt } from '../utils/board'
import { resolveMediaUrl } from '../utils/media'
import { checkRole, eventRoleIds } from '../utils/roleCompleteness'
import tavernScene from '../assets/scenes/tavern.webp'

const props = defineProps<{ summary: DashboardSummary | null }>()
const emit = defineEmits<{ refresh: [] }>()

const boards = ref<Board[]>([])
const roles = ref<Role[]>([])
const reports = ref<ReportItem[]>([])
const worldEvents = ref<WorldEvent[]>([])

const rolesById = computed(() => new Map(roles.value.map((role) => [role.id, role])))
const featured = computed(() =>
  featuredBoards(boards.value).map((board) => ({
    board,
    cover: resolveMediaUrl(board.backgroundImage),
    art: board.backgroundImage ? null : pickLineupArt(board.roles, rolesById.value, 1)[0]?.role,
  })),
)
const openReports = computed(() => reports.value.filter((report) => report.processStatus === 'OPEN'))
/** 设定不完整的角色（规则在 utils/roleCompleteness.ts），以及各项分别缺了几个。 */
const roleGaps = computed(() => {
  const byId = new Map(roles.value.map((role) => [role.id, role]))
  const cast = eventRoleIds(worldEvents.value)
  const counts = new Map<string, number>()
  let incomplete = 0
  roles.value.forEach((role) => {
    const result = checkRole(role, byId, cast)
    if (!result.complete) incomplete += 1
    result.missing.forEach((item) => counts.set(item.label, (counts.get(item.label) || 0) + 1))
  })
  const top = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3)
  return { incomplete, detail: top.map(([label, count]) => `缺${label.replace(/ .*$/, '')} ${count} 个`).join('、') }
})

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 5) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const headline = computed(() => {
  const s = props.summary
  if (!s) return '正在清点夜镇…'
  const parts: string[] = []
  if (s.openReportCount) parts.push(`有 ${s.openReportCount} 条举报等你处理`)
  if (s.boardsWithoutBackground) parts.push(`${s.boardsWithoutBackground} 个板子还没挂背景图`)
  return parts.length ? `今天${parts.join('，')}。` : '今晚风平浪静，没有要处理的事。'
})

const todos = computed(() => {
  const s = props.summary
  if (!s) return []
  const list: { key: string; href: string; tone: string; icon: string; title: string; detail: string }[] = []
  if (s.openReportCount) {
    list.push({
      key: 'reports',
      href: '#/community',
      tone: 'var(--camp-wolf)',
      icon: 'M5 21V4h11l-1.5 4L16 12H5',
      title: `${s.openReportCount} 条举报待处理`,
      detail: openReports.value[0]?.reason || '去社区治理里看看',
    })
  }
  if (s.boardsWithoutBackground) {
    list.push({
      key: 'backgrounds',
      href: '#/boards',
      tone: 'var(--gold)',
      icon: 'M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6',
      title: `${s.boardsWithoutBackground} 个板子没有背景图`,
      detail: '列表卡片会改用阵容立绘，首页推荐排在后面',
    })
  }
  if (roleGaps.value.incomplete) {
    list.push({
      key: 'roles',
      href: '#/roles',
      tone: 'var(--camp-third)',
      icon: 'M5 3h14v18H5zM9 8h6M9 12h6',
      title: `${roleGaps.value.incomplete} 个角色设定不完整`,
      detail: `${roleGaps.value.detail}，图鉴里点「只看设定不完整」逐个补`,
    })
  }
  if (s.assistantFailuresToday) {
    list.push({
      key: 'ai',
      href: '#/ai',
      tone: 'var(--moon)',
      icon: 'M12 3v4M12 17v4M3 12h4M17 12h4',
      title: `AI 助手今天回答失败 ${s.assistantFailuresToday} 次`,
      detail: '在 AI 助手的问答日志里看失败原因',
    })
  }
  return list
})

async function load() {
  emit('refresh')
  const [boardData, roleData, reportData, worldData] = await Promise.allSettled([api.getBoards(), api.getRoles(), api.getReports(), api.getWorld()])
  if (boardData.status === 'fulfilled') boards.value = boardData.value
  if (roleData.status === 'fulfilled') roles.value = roleData.value
  if (reportData.status === 'fulfilled') reports.value = reportData.value.list
  if (worldData.status === 'fulfilled') worldEvents.value = worldData.value.events || []
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="TONIGHT IN TOWN" :title="`${greeting}，管理员`" :description="headline" :banner="tavernScene" />

  <section class="stats" aria-label="数据">
    <a class="stat" href="#/boards">
      <span class="stat__label">板子</span>
      <strong>{{ summary?.boardCount ?? '--' }}</strong>
      <small v-if="summary">{{ summary.onlineBoardCount }} 上架 · {{ summary.boardCount - summary.onlineBoardCount }} 下架</small>
    </a>
    <a class="stat" href="#/roles">
      <span class="stat__label">角色</span>
      <strong>{{ summary?.roleCount ?? '--' }}</strong>
      <small v-if="summary">{{ summary.roleCount - summary.rolesWithoutIllustration }} 个有立绘</small>
    </a>
    <a class="stat" href="#/community">
      <span class="stat__label">帖子</span>
      <strong>{{ summary?.postCount ?? '--' }}</strong>
      <small v-if="summary">评论 {{ summary.commentCount }} 条</small>
    </a>
    <a class="stat" :class="{ 'stat--alert': summary?.openReportCount }" href="#/community">
      <span class="stat__label">待处理举报</span>
      <strong>{{ summary?.openReportCount ?? '--' }}</strong>
      <small>{{ summary?.openReportCount ? '尽快处理' : '暂时没有' }}</small>
    </a>
    <a class="stat" href="#/ai">
      <span class="stat__label">AI 今日提问</span>
      <strong>{{ summary?.assistantQuestionsToday ?? '--' }}</strong>
      <small v-if="summary">失败 {{ summary.assistantFailuresToday }} 次</small>
    </a>
  </section>

  <div class="cols">
    <section class="nt-panel" aria-labelledby="todo-title">
      <h2 id="todo-title" class="nt-h2">要处理的事</h2>
      <a v-for="todo in todos" :key="todo.key" class="todo" :href="todo.href">
        <span class="todo__icon" :style="{ background: todo.tone }">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="todo.icon" /></svg>
        </span>
        <span class="todo__text">
          <strong>{{ todo.title }}</strong>
          <span>{{ todo.detail }}</span>
        </span>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
      </a>
      <p v-if="summary && !todos.length" class="nt-empty">今晚风平浪静，没有要处理的事。</p>
    </section>

    <section class="nt-panel" aria-labelledby="featured-title">
      <div class="nt-row-between">
        <h2 id="featured-title" class="nt-h2">首页「今晚推荐」</h2>
        <a href="#/boards" class="link">去调整</a>
      </div>
      <p class="nt-muted">小程序首页走马灯：已上架的板子里，有背景图的排前面，最多 5 个。</p>
      <ol class="featured">
        <li v-for="(item, index) in featured" :key="item.board.id">
          <span class="featured__no">{{ index + 1 }}</span>
          <img v-if="item.cover" class="featured__img" :src="item.cover" alt="" />
          <span v-else class="featured__img nt-cardback featured__art">
            <img v-if="item.art" :src="resolveMediaUrl(item.art.fullIllustration || item.art.portrait)" alt="" />
            <small v-else>阵容立绘</small>
          </span>
          <a class="featured__name" :href="`#/boards/${item.board.id}`">{{ item.board.name }}</a>
          <span class="featured__meta">{{ item.board.playerCount }} 人 · {{ item.board.difficulty }}</span>
        </li>
      </ol>
    </section>
  </div>

  <section v-if="summary?.recentBoards?.length" class="nt-panel" aria-labelledby="recent-title">
    <h2 id="recent-title" class="nt-h2">最近改过的板子</h2>
    <a v-for="item in summary.recentBoards" :key="item.id" class="recent" :href="`#/boards/${item.id}`">
      <span class="recent__time">{{ formatTime(item.updateTime) }}</span>
      <span><strong>{{ item.name }}</strong> 有改动</span>
    </a>
  </section>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 16px;
}

.stat {
  padding: 20px;
  border-radius: 20px;
  background: var(--panel);
  border: 2px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: var(--text);
  text-decoration: none;
}

.stat:hover {
  background: var(--panel-raised);
}

.stat__label,
.stat small {
  font-size: 13px;
  color: var(--text-3);
}

.stat small {
  font-size: 12px;
}

.stat strong {
  font: 900 34px var(--serif);
}

.stat--alert {
  background: var(--danger-bg);
}

.stat--alert .stat__label,
.stat--alert small {
  color: #ffb1a9;
}

.stat--alert strong {
  color: var(--camp-wolf);
}

.cols {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  align-items: flex-start;
}

.cols > .nt-panel {
  flex: 1 1 400px;
}

.todo {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-radius: 16px;
  background: var(--panel-raised);
  color: var(--text-3);
  text-decoration: none;
}

.todo:hover {
  background: var(--panel-line);
}

.todo__icon {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 12px;
  border: 2px solid var(--ink);
  color: var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
}

.todo__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}

.todo__text strong {
  font-size: 15px;
  color: var(--text);
}

.link {
  font-size: 14px;
  text-decoration: none;
}

.featured {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.featured li {
  display: flex;
  align-items: center;
  gap: 12px;
}

.featured__no {
  width: 22px;
  font: 900 16px var(--serif);
  color: var(--gold);
}

.featured__img {
  width: 96px;
  height: 54px;
  flex: none;
  border-radius: 10px;
  border: 2px solid var(--ink);
  object-fit: cover;
  overflow: hidden;
}

.featured__art {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--text-3);
}

.featured__art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 50% 15%;
}

.featured__name {
  font: 900 16px var(--serif);
  color: var(--text);
  text-decoration: none;
}

.featured__name:hover {
  color: var(--moon);
}

.featured__meta {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-3);
  white-space: nowrap;
}

.recent {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  padding: 10px 0;
  border-bottom: 1px solid var(--panel-line);
  color: var(--text-soft);
  font-size: 14px;
  text-decoration: none;
}

.recent:last-child {
  border-bottom: 0;
}

.recent:hover strong {
  color: var(--moon);
}

.recent__time {
  width: 110px;
  color: var(--text-3);
}
</style>
