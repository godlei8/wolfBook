// 板子详细规则和角色主题色的纯函数，services/api.js 用它整理接口数据，tests/board-rules.test.mjs 覆盖。

/** 角色没配主题色时按阵营兜底。 */
export const FACTION_ACCENTS = {
  好人: '#D4A73A',
  狼人: '#C0262D',
  第三方: '#F28CB8',
}

export const DEFAULT_ACCENT = '#D4A73A'

const HEX_COLOR = /^#[0-9A-Fa-f]{6}$/

export function roleAccent(role) {
  if (role && typeof role.themeColor === 'string' && HEX_COLOR.test(role.themeColor)) {
    return role.themeColor.toUpperCase()
  }
  return (role && FACTION_ACCENTS[role.faction]) || DEFAULT_ACCENT
}

/** 把 #RRGGBB 转成带透明度的 rgba，用于卡片的淡色底。非法颜色回退到默认色。 */
export function withAlpha(hex, alpha) {
  const color = typeof hex === 'string' && HEX_COLOR.test(hex) ? hex : DEFAULT_ACCENT
  const value = Number.parseInt(color.slice(1), 16)
  const r = (value >> 16) & 255
  const g = (value >> 8) & 255
  const b = value & 255
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function lineupGroup(role) {
  if (role.faction === '狼人') return 'wolf'
  if (role.faction === '第三方') return 'third'
  if (role.roleType === '平民') return 'villager'
  return 'god'
}

/** 阵营比例条的顺序、文案和颜色（原型 D 的阵营色，夜色底上都看得清）。 */
export const CAMP_SEGMENTS = [
  { key: 'god', label: '神职', fill: '#D4A73A', text: '#E9C46A' },
  { key: 'villager', label: '平民', fill: '#4FA877', text: '#8FD0A6' },
  { key: 'third', label: '第三方', fill: '#F28CB8', text: '#F7A8CB' },
  { key: 'wolf', label: '狼人', fill: '#E0403A', text: '#FF7468' },
]

/** 角色所属的阵营段（神职 / 平民 / 第三方 / 狼人），带文案和颜色。 */
export function campSegment(role) {
  if (!role) return CAMP_SEGMENTS[0]
  return CAMP_SEGMENTS.find((item) => item.key === lineupGroup(role)) || CAMP_SEGMENTS[0]
}

/** 角色名在夜色底上的文字颜色：按阵营取，平民单独一色。 */
export function campTextColor(role) {
  return campSegment(role).text
}

/** 板子详情的阵容分组：按阵营顺序给出每组的角色和总张数，空组不返回。 */
export function groupLineup(roles) {
  const list = (Array.isArray(roles) ? roles : []).filter(Boolean)
  return CAMP_SEGMENTS.map((segment) => {
    const items = list.filter((role) => lineupGroup(role) === segment.key)
    const count = items.reduce((sum, role) => sum + (Number(role.count) || 0), 0)
    return { ...segment, roles: items, count }
  }).filter((group) => group.roles.length)
}

/** 按阵营统计阵容张数，只返回人数大于 0 的段，供卡片底部的比例条用。 */
export function campBreakdown(roles) {
  const totals = {}
  for (const role of Array.isArray(roles) ? roles : []) {
    if (!role) continue
    const count = Number(role.count) || 0
    if (count <= 0) continue
    const key = lineupGroup(role)
    totals[key] = (totals[key] || 0) + count
  }
  return CAMP_SEGMENTS.filter((segment) => totals[segment.key]).map((segment) => ({ ...segment, count: totals[segment.key] }))
}

/**
 * 板子没配背景图时，列表卡片顶部摆三张阵容立绘：中间放最有辨识度的狼（狼美人、黑狼王这类，
 * 没有就用普通狼人），两边放前两个神职；不够时依次用第三方、平民、其他狼补。
 * 立绘优先用 fullIllustration，没有时退回头像；一张图都没有的角色不参与。
 * 返回按「左、中、右」排好的数组，可能少于三张。
 */
export function pickLineupArt(roles) {
  const candidates = (Array.isArray(roles) ? roles : [])
    .filter(Boolean)
    .map((role) => ({ role, image: role.fullIllustration || role.portrait || '' }))
    .filter((item) => item.image)
  const byGroup = (group) => candidates.filter((item) => lineupGroup(item.role) === group)
  const wolves = byGroup('wolf')
  const center = wolves.find((item) => item.role.name !== '狼人') || wolves[0] || byGroup('third')[0] || candidates[0]
  if (!center) return []

  const sides = [...byGroup('god'), ...byGroup('third'), ...byGroup('villager'), ...wolves]
    .filter((item) => item !== center)
    .slice(0, 2)
  const ordered = sides.length === 2 ? [sides[0], center, sides[1]] : [...sides, center]
  return ordered.map((item) => ({
    roleId: item.role.roleId,
    name: item.role.name,
    image: item.image,
    accent: roleAccent(item.role),
    center: item === center,
  }))
}

function cleanItems(items) {
  return (Array.isArray(items) ? items : [])
    .map((item) => (typeof item === 'string' ? item.trim() : ''))
    .filter(Boolean)
}

/**
 * 整理接口返回的 ruleDetail：补齐空数组、解析图片地址、给每一步和每个角色算好强调色，
 * 并带上 hasContent，页面据此决定显示新版规则还是旧的「规则详情」列表。
 */
export function normalizeRuleDetail(detail, resolveUrl = (url) => url) {
  const source = detail || {}
  const overview = typeof source.overview === 'string' ? source.overview.trim() : ''

  const nightOrder = (Array.isArray(source.nightOrder) ? source.nightOrder : [])
    .filter((step) => step && step.roleId && typeof step.action === 'string' && step.action.trim())
    .map((step, index) => ({
      ...step,
      order: step.order || index + 1,
      action: step.action.trim(),
      portrait: resolveUrl(step.portrait),
      accent: roleAccent(step),
      campColor: campTextColor(step),
    }))

  const roleRules = (Array.isArray(source.roleRules) ? source.roleRules : [])
    .map((note) => ({
      ...note,
      items: cleanItems(note && note.items),
      portrait: resolveUrl(note && note.portrait),
      accent: roleAccent(note),
      campColor: campTextColor(note),
    }))
    .filter((note) => note.roleId && note.items.length)

  const sections = (Array.isArray(source.sections) ? source.sections : [])
    .map((section) => ({
      title: section && typeof section.title === 'string' ? section.title.trim() : '',
      items: cleanItems(section && section.items),
    }))
    .filter((section) => section.title && section.items.length)

  return {
    overview,
    nightOrder,
    roleRules,
    sections,
    hasContent: Boolean(overview || nightOrder.length || roleRules.length || sections.length),
  }
}
