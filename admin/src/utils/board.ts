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

// 电子法官按角色的技能机制编排夜晚，和后端 service/judge/Mechanic、NightPlan 同口径：
// 没配夜间顺序时按机制优先级排（守卫 → 狼队 → 女巫 → 预言家 → 猎人），没有机制的角色排在最后、只播报
export const MECHANIC_LABELS: Record<string, string> = {
  villager: '平民',
  wolf: '狼人',
  guard: '守卫',
  witch: '女巫',
  seer: '预言家',
  hunter: '猎人',
  idiot: '白痴',
}
const MECHANIC_PRIORITY: Record<string, number> = { guard: 1, wolf: 2, witch: 3, seer: 4, hunter: 5 }
const SOLO_WOLVES = ['石像鬼', '机械狼']
const DEFAULT_ACTIONS: Record<string, string> = {
  狼人: '狼人请睁眼，今晚要刀谁？',
  女巫: '女巫请睁眼，今晚他倒下了，你要用解药吗？你要用毒药吗？',
  预言家: '预言家请睁眼，你要查验谁？',
  通灵师: '通灵师请睁眼，你要查验谁？',
  守卫: '守卫请睁眼，你今晚要守护谁？',
  猎人: '猎人请睁眼，确认你今晚的开枪状态。',
}

/** 跟狼队一起睁眼刀人的角色（法官主持台会把它们合成一步）：有机制看机制，没有机制按阵营（排除石像鬼、机械狼）。 */
export function isWolfTeam(role?: Role | null) {
  if (!role) return false
  if (role.mechanic) return role.mechanic === 'wolf'
  return role.faction === '狼人' && !SOLO_WOLVES.includes(role.name)
}

/** 机制对应的那一句台词（角色换了名字也用角色名开头）。 */
function stepLine(role: Role) {
  const byMechanic: Record<string, string> = {
    wolf: DEFAULT_ACTIONS['狼人'],
    witch: DEFAULT_ACTIONS['女巫'],
    seer: DEFAULT_ACTIONS['预言家'],
    guard: DEFAULT_ACTIONS['守卫'],
    hunter: DEFAULT_ACTIONS['猎人'],
  }
  const line = role.mechanic ? byMechanic[role.mechanic] : undefined
  if (!line) return `${role.name}请睁眼，请行动。`
  const original = Object.keys(DEFAULT_ACTIONS).find((name) => DEFAULT_ACTIONS[name] === line) || ''
  return original ? line.replace(original, role.name) : line
}

/** 按角色的技能机制从阵容里生成夜间步骤（狼队合成一步，没有机制的角色排最后、只播报），和后端 NightPlan 一致。 */
export function defaultNightOrder(lineup: BoardRoleItem[], rolesById: Map<number, Role>): NightStep[] {
  const roles = lineup.map((item) => rolesById.get(item.roleId)).filter((role): role is Role => Boolean(role))
  const priority = (role: Role) => (isWolfTeam(role) ? MECHANIC_PRIORITY.wolf : role.mechanic ? MECHANIC_PRIORITY[role.mechanic] : undefined)
  const ranked = roles.filter((role) => priority(role) !== undefined).sort((a, b) => (priority(a) || 0) - (priority(b) || 0))
  const unknown = roles.filter((role) => priority(role) === undefined && !role.mechanic)
  const steps: NightStep[] = []
  let wolvesDone = false
  for (const role of ranked) {
    if (isWolfTeam(role)) {
      if (wolvesDone) continue
      wolvesDone = true
      const wolves = roles.filter(isWolfTeam)
      const lead = wolves.find((item) => item.mechanic === 'wolf') || wolves[0]
      steps.push({ roleId: lead.id, action: DEFAULT_ACTIONS['狼人'] })
      continue
    }
    steps.push({ roleId: role.id, action: stepLine(role) })
  }
  unknown.forEach((role) => steps.push({ roleId: role.id, action: `${role.name}请睁眼，请行动。` }))
  return steps
}

export type JudgeSupportLevel = 'full' | 'partial' | 'manual_only'

export const JUDGE_SUPPORT_META: Record<JudgeSupportLevel, { label: string; tone: 'ok' | 'warn' | 'off' }> = {
  full: { label: '两种模式都能开', tone: 'ok' },
  partial: { label: '只能开有法官', tone: 'warn' },
  manual_only: { label: '电子法官只能手动', tone: 'off' },
}

/** 板子的电子法官支持级别（和后端 RoleSkillRegistry.supportLevel 同口径），missing 是没有机制的角色名。 */
export function judgeSupport(lineup: BoardRoleItem[], rolesById: Map<number, Role>) {
  const roles = lineup.map((item) => rolesById.get(item.roleId)).filter((role): role is Role => Boolean(role))
  const missing = roles.filter((role) => !role.mechanic).map((role) => role.name)
  const third = roles.some((role) => role.faction === '第三方')
  let level: JudgeSupportLevel = 'partial'
  if (!roles.length || missing.length === roles.length) level = 'manual_only'
  else if (!missing.length && !third) level = 'full'
  return { level, missing, third }
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
