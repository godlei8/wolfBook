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
    }))

  const roleRules = (Array.isArray(source.roleRules) ? source.roleRules : [])
    .map((note) => ({
      ...note,
      items: cleanItems(note && note.items),
      portrait: resolveUrl(note && note.portrait),
      accent: roleAccent(note),
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
