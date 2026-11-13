// 建房页的纯逻辑（有单测）：房间规则的默认值和步进范围、建房请求、板子玩法规则（只读）。
import { buildNightSteps } from './night-order.js'
// 房间规则只管节奏（上警、发言时长、行动时长）；胜负、女巫自救跟板子走，一晚一瓶是全局规则，建房页只读。

export const ROOM_RULE_DEFAULTS = {
  sheriffEnabled: true,
  speechSeconds: 120,
  sheriffSpeechSeconds: 150,
  wolfSeconds: 60,
  roleSeconds: 30,
}

// 和后端 applyRoomRules 的范围一致：发言 / 警上发言 30–300，狼队 30–120，单人 15–60。
export const ROOM_RULE_STEPS = {
  speechSeconds: { min: 30, max: 300, step: 30, label: '发言时长' },
  sheriffSpeechSeconds: { min: 30, max: 300, step: 30, label: '警上发言' },
  wolfSeconds: { min: 30, max: 120, step: 10, label: '狼队行动' },
  roleSeconds: { min: 15, max: 60, step: 5, label: '单人角色行动' },
}

export function defaultRoomRules(board) {
  const configured = board?.ruleDetail?.judgeRules || {}
  const pick = (key) => (Number(configured[key]) > 0 ? Number(configured[key]) : ROOM_RULE_DEFAULTS[key])
  return {
    sheriffEnabled: typeof configured.sheriffDefault === 'boolean' ? configured.sheriffDefault : ROOM_RULE_DEFAULTS.sheriffEnabled,
    speechSeconds: pick('speechSeconds'),
    sheriffSpeechSeconds: pick('sheriffSpeechSeconds'),
    wolfSeconds: pick('wolfSeconds'),
    roleSeconds: pick('roleSeconds'),
  }
}

/** direction 为 +1 / −1，按这一项的步长走一格，越界夹住。 */
export function stepRule(value, key, direction) {
  const range = ROOM_RULE_STEPS[key]
  if (!range) return value
  const next = (Number(value) || range.min) + Math.sign(direction) * range.step
  return Math.min(range.max, Math.max(range.min, next))
}

export function buildCreatePayload(board, mode, rules) {
  return { boardId: board?.id, judgeMode: mode, autoJudge: true, rules }
}

export function canChooseJoined(board) {
  return board?.judgeSupportLevel === 'full'
}

export function boardRuleLine(board) {
  const rules = board?.ruleDetail?.judgeRules || {}
  const city = rules.winCondition ? rules.winCondition === 'city' : String(board?.winCondition || '').includes('屠城')
  const parts = [city ? '屠城' : '屠边']
  const hasWitch = (board?.roles || []).some((role) => role?.name === '女巫')
  if (hasWitch) {
    // 全程不可自救、一晚一瓶都是全局规则（roles/女巫.md）
    parts.push('女巫不能自救', '一晚一瓶')
  }
  return parts.join(' · ')
}

/** 建房第二步「板子配置」：跟板子走、房间里改不了的玩法规则（胜负、女巫、弹毒、夜间顺序），只读。 */
export function boardConfigRows(board) {
  if (!board) return []
  const rules = board.ruleDetail?.judgeRules || {}
  const names = (board.roles || []).map((role) => role?.name)
  const city = rules.winCondition ? rules.winCondition === 'city' : String(board.winCondition || '').includes('屠城')
  const rows = [{ label: '胜负', value: city ? '屠城' : '屠边' }]
  if (names.includes('女巫')) rows.push({ label: '女巫', value: '不能自救 · 一晚一瓶' })
  if (names.includes('机械狼')) rows.push({ label: '弹毒', value: rules.poisonBounce ? '开' : '关' })
  const steps = buildNightSteps(board).map((step) => step.roleName)
  if (steps.length) rows.push({ label: '夜间顺序', value: steps.join(' → ') })
  return rows
}
