// 法官主持台的夜间步骤：板子配了 ruleDetail.nightOrder 就按它走，没配时按常见角色的通用顺序兜底。
// 每一步带 kind（wolf / witch / seer / guard / hunter / generic），主持台据此给出台词和代记按钮。
// 纯函数，tests/judge-night-order.test.mjs 覆盖。

// 通用夜间顺序（只列夜里要睁眼的角色）；狼人这一步代表整个狼队
export const DEFAULT_NIGHT_ORDER = ['丘比特', '盗贼', '混血儿', '守卫', '摄梦人', '狼人', '石像鬼', '女巫', '预言家', '通灵师', '守墓人', '猎人', '乌鸦', '魔术师']

// 狼人阵营里单独睁眼、不跟狼队一起刀人的角色
const SOLO_WOLVES = ['石像鬼', '机械狼']

/** 跟狼队一起睁眼刀人的角色。 */
export function isWolfTeam(role) {
  if (!role) return false
  const name = role.roleName || role.name || ''
  return role.faction === '狼人' && !SOLO_WOLVES.includes(name)
}

/** 某一步对应的行动类型。 */
export function stepKind(step) {
  if (!step) return 'generic'
  if (step.team === 'wolf') return 'wolf'
  const name = step.roleName || ''
  if (name.includes('女巫')) return 'witch'
  if (name.includes('预言家') || name.includes('通灵师')) return 'seer'
  if (name.includes('守卫')) return 'guard'
  if (name.includes('猎人')) return 'hunter'
  return 'generic'
}

const DEFAULT_ACTIONS = {
  wolf: '狼人请睁眼，今晚要刀谁？',
  witch: '女巫请睁眼，今晚他倒下了，你要用解药吗？你要用毒药吗？',
  seer: '预言家请睁眼，你要查验谁？',
  guard: '守卫请睁眼，你今晚要守护谁？',
  hunter: '猎人请睁眼，确认你今晚的开枪状态。',
}

function stepFromRole(role, extra = {}) {
  return {
    roleId: role.roleId,
    roleName: role.name,
    faction: role.faction,
    portrait: role.portrait || '',
    fullIllustration: role.fullIllustration || '',
    accent: role.accent,
    campColor: role.campColor,
    ...extra,
  }
}

/**
 * 主持台的夜间步骤。roles 是板子详情里整理过的角色（roleId、name、faction、portrait、accent…）。
 * 配了 nightOrder：按它走，狼人那一步标成 team = wolf（整个狼队一起睁眼）。
 * 没配：按 DEFAULT_NIGHT_ORDER 从阵容里挑出夜里要睁眼的角色。
 */
export function buildNightSteps(board) {
  const roles = Array.isArray(board?.roles) ? board.roles : []
  const byId = {}
  roles.forEach((role) => {
    byId[role.roleId] = role
  })
  const configured = board?.ruleDetail?.nightOrder || []
  let steps
  if (configured.length) {
    let wolfDone = false
    steps = configured
      .map((step) => {
        const role = byId[step.roleId] || {}
        const merged = { ...step, faction: step.faction || role.faction, fullIllustration: role.fullIllustration || '' }
        if (isWolfTeam(merged)) {
          if (wolfDone) return null
          wolfDone = true
          return { ...merged, team: 'wolf' }
        }
        return merged
      })
      .filter(Boolean)
  } else {
    steps = []
    DEFAULT_NIGHT_ORDER.forEach((name) => {
      if (name === '狼人') {
        const wolves = roles.filter(isWolfTeam)
        if (!wolves.length) return
        const lead = wolves.find((role) => role.name === '狼人') || wolves[0]
        steps.push(stepFromRole(lead, { roleName: '狼人', team: 'wolf' }))
        return
      }
      const role = roles.find((item) => item.name === name)
      if (role) steps.push(stepFromRole(role))
    })
  }
  return steps.map((step, index) => ({
    ...step,
    order: index + 1,
    kind: stepKind(step),
    action: step.action || DEFAULT_ACTIONS[stepKind(step)] || `${step.roleName}请睁眼，请行动。`,
  }))
}
