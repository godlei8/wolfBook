// 建房页的纯逻辑（有单测）：房间规则的默认值和步进范围、建房请求、板子玩法规则的只读一行。
// 房间规则只管节奏（上警、发言时长、行动时长）；胜负、女巫自救、一晚一瓶跟板子走，建房页只读。

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

const SELF_SAVE_TEXT = {
  first_night: '女巫只首夜可自救',
  never: '女巫不能自救',
}

export function boardRuleLine(board) {
  const rules = board?.ruleDetail?.judgeRules || {}
  const city = rules.winCondition ? rules.winCondition === 'city' : String(board?.winCondition || '').includes('屠城')
  const parts = [city ? '屠城' : '屠边']
  const hasWitch = (board?.roles || []).some((role) => role?.name === '女巫')
  if (hasWitch) {
    parts.push(SELF_SAVE_TEXT[rules.witchSelfSave || 'first_night'] || SELF_SAVE_TEXT.first_night)
    parts.push(rules.witchOnePotionPerNight === false ? '一晚可用两瓶' : '一晚一瓶')
  }
  return parts.join(' · ')
}
