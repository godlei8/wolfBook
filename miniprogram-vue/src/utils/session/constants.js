export const SESSION_PHASES = [
  'night',
  'sheriff_race',
  'day_speech',
  'exile_vote',
  'last_words',
  'result',
]

export const SESSION_PHASE_LABEL_MAP = {
  night: '夜间',
  sheriff_race: '警上',
  day_speech: '白天发言',
  exile_vote: '放逐投票',
  last_words: '遗言',
  result: '结算',
}

export const SESSION_PHASE_ORDER = {
  night: 1,
  sheriff_race: 2,
  day_speech: 3,
  exile_vote: 4,
  last_words: 5,
  result: 6,
}

export const SESSION_SCENE_LABEL_MAP = {
  night: '夜间',
  speech: '发言',
  vote: '投票',
  identity: '身份',
  note: '备注',
}

export const SESSION_STATUS_LABEL_MAP = {
  active: '进行中',
  finished: '已结束',
  archived: '已归档',
}

export const RECORD_TYPE_LABEL_MAP = {
  seer: '查验',
  nightAction: '夜间',
  vote: '投票',
  speech: '发言',
  wolfPack: '狼坑',
  identity: '身份',
  note: '备注',
}

export const DEATH_REASON_LABEL_MAP = {
  knife: '刀口',
  poison: '毒杀',
  vote: '放逐',
  skill: '技能',
  other: '其他',
}

export const RESULT_CAMP_OPTIONS = [
  { value: '', label: '未填写结果' },
  { value: 'good', label: '好人胜' },
  { value: 'wolf', label: '狼人胜' },
  { value: 'third', label: '第三方胜' },
]

export function getPhaseLabel(phase) {
  return SESSION_PHASE_LABEL_MAP[phase] || '局内阶段'
}

export function getSceneLabel(scene) {
  return SESSION_SCENE_LABEL_MAP[scene] || '局内记录'
}

export function getStatusLabel(status) {
  return SESSION_STATUS_LABEL_MAP[status] || '进行中'
}

export function getRecordTypeLabel(type) {
  return RECORD_TYPE_LABEL_MAP[type] || '局内记录'
}

export function getDeathReasonLabel(reason) {
  return DEATH_REASON_LABEL_MAP[reason] || ''
}
