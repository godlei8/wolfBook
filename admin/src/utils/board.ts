import type { Board, BoardRoleItem, NightStep, Role } from '../types'

/** 阵营分组，和小程序 utils/board-rules.js 的 lineupGroup 一致。 */
export type CampKey = 'god' | 'villager' | 'wolf' | 'third'

export const CAMP_META: Record<CampKey, { label: string; color: string }> = {
  god: { label: '神职', color: '#e9c46a' },
  villager: { label: '平民', color: '#8fd0a6' },
  wolf: { label: '狼人', color: '#ff7468' },
  third: { label: '第三方', color: '#f7a8cb' },
}

export const CAMP_ORDER: CampKey[] = ['god', 'villager', 'wolf', 'third']

export function campOf(role?: Pick<Role, 'faction' | 'roleType'> | null): CampKey {
  if (!role) return 'god'
  if (role.faction === '狼人') return 'wolf'
  if (role.faction === '第三方') return 'third'
  if (role.roleType === '平民') return 'villager'
  return 'god'
}

/** 角色的强调色：专属主题色优先，没有就按阵营。 */
export function roleAccent(role?: Role | null) {
  return role?.themeColor || CAMP_META[campOf(role)].color
}

export function roleImage(role?: Role | null) {
  return role?.fullIllustration || role?.portrait || ''
}

export function lineupSize(lineup: BoardRoleItem[]) {
  return lineup.reduce((sum, item) => sum + (Number(item.count) || 0), 0)
}

export function campCounts(lineup: BoardRoleItem[], rolesById: Map<number, Role>) {
  const counts: Record<CampKey, number> = { god: 0, villager: 0, wolf: 0, third: 0 }
  lineup.forEach((item) => {
    const role = rolesById.get(item.roleId)
    if (role) counts[campOf(role)] += Number(item.count) || 0
  })
  return counts
}

/** 阵营比例条的每一段，空的阵营不画。 */
export function campSegments(lineup: BoardRoleItem[], rolesById: Map<number, Role>) {
  const counts = campCounts(lineup, rolesById)
  return CAMP_ORDER.filter((key) => counts[key] > 0).map((key) => ({
    key,
    count: counts[key],
    label: CAMP_META[key].label,
    color: CAMP_META[key].color,
  }))
}

/** 阵容按 神职 → 平民 → 狼人 → 第三方 排，同组保持原顺序。 */
export function sortLineup(lineup: BoardRoleItem[], rolesById: Map<number, Role>) {
  return [...lineup].sort(
    (a, b) => CAMP_ORDER.indexOf(campOf(rolesById.get(a.roleId))) - CAMP_ORDER.indexOf(campOf(rolesById.get(b.roleId))),
  )
}

/**
 * 列表卡片没有背景图时摆的三张阵容立绘：中间放特殊狼，两边放神职。
 * 和小程序 utils/board-rules.js 的 pickLineupArt 同一套挑法。
 */
export function pickLineupArt(lineup: BoardRoleItem[], rolesById: Map<number, Role>, limit = 3) {
  const roles = lineup.map((item) => rolesById.get(item.roleId)).filter((role): role is Role => Boolean(role))
  const candidates = roles.filter((role) => roleImage(role))
  const byCamp = (camp: CampKey) => candidates.filter((role) => campOf(role) === camp)
  const wolves = byCamp('wolf')
  const center = wolves.find((role) => role.name !== '狼人') || wolves[0] || byCamp('third')[0] || candidates[0]
  if (!center) return []
  const sides = [...byCamp('god'), ...byCamp('third'), ...byCamp('villager'), ...wolves]
    .filter((role) => role !== center)
    .slice(0, Math.max(0, limit - 1))
  const left: Role[] = []
  const right: Role[] = []
  sides.forEach((role, index) => (index % 2 === 0 ? left : right).push(role))
  return [...left.reverse(), center, ...right].map((role) => ({ role, center: role === center }))
}

// 小程序首页：第一页（20 个）里有背景图的排前面，取前 5 个
const HOME_FIRST_PAGE = 20
const FEATURED_LIMIT = 5

/** 小程序首页「今晚推荐」会展示哪几个板子，按顺序。 */
export function featuredBoards(boards: Board[]) {
  const firstPage = boards
    .filter((board) => board.status === 1)
    .sort((a, b) => a.id - b.id)
    .slice(0, HOME_FIRST_PAGE)
  const withBackground = firstPage.filter((board) => board.backgroundImage)
  const rest = firstPage.filter((board) => !board.backgroundImage)
  return withBackground.concat(rest).slice(0, FEATURED_LIMIT)
}

// 夜间顺序的通用兜底，和小程序 utils/judge/night-order.js 一致
const DEFAULT_NIGHT_ORDER = ['丘比特', '盗贼', '混血儿', '守卫', '摄梦人', '狼人', '石像鬼', '女巫', '预言家', '通灵师', '守墓人', '猎人', '乌鸦', '魔术师']
const SOLO_WOLVES = ['石像鬼', '机械狼']
const DEFAULT_ACTIONS: Record<string, string> = {
  狼人: '狼人请睁眼，今晚要刀谁？',
  女巫: '女巫请睁眼，今晚他倒下了，你要用解药吗？你要用毒药吗？',
  预言家: '预言家请睁眼，你要查验谁？',
  通灵师: '通灵师请睁眼，你要查验谁？',
  守卫: '守卫请睁眼，你今晚要守护谁？',
  猎人: '猎人请睁眼，确认你今晚的开枪状态。',
}

/** 跟狼队一起睁眼刀人的角色（法官主持台会把它们合成一步）。 */
export function isWolfTeam(role?: Role | null) {
  return Boolean(role && role.faction === '狼人' && !SOLO_WOLVES.includes(role.name))
}

/** 按通用顺序从阵容里生成夜间步骤，狼队合成「狼人」一步。 */
export function defaultNightOrder(lineup: BoardRoleItem[], rolesById: Map<number, Role>): NightStep[] {
  const roles = lineup.map((item) => rolesById.get(item.roleId)).filter((role): role is Role => Boolean(role))
  const steps: NightStep[] = []
  DEFAULT_NIGHT_ORDER.forEach((name) => {
    if (name === '狼人') {
      const wolves = roles.filter(isWolfTeam)
      if (!wolves.length) return
      const lead = wolves.find((role) => role.name === '狼人') || wolves[0]
      steps.push({ roleId: lead.id, action: DEFAULT_ACTIONS['狼人'] })
      return
    }
    const role = roles.find((item) => item.name === name)
    if (role) steps.push({ roleId: role.id, action: DEFAULT_ACTIONS[name] || `${name}请睁眼，请行动。` })
  })
  return steps
}

export function formatTime(value?: string | null) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const hm = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  if (date.toDateString() === now.toDateString()) return `今天 ${hm}`
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (date.toDateString() === yesterday.toDateString()) return `昨天 ${hm}`
  const md = `${date.getMonth() + 1}-${pad(date.getDate())}`
  return date.getFullYear() === now.getFullYear() ? `${md} ${hm}` : `${date.getFullYear()}-${md}`
}
