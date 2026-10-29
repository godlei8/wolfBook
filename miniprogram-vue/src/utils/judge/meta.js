export const judgeModeOptions = [
  {
    value: 'observer',
    title: '主持人',
    subtitle: '法官不入局，拥有完整控制台',
  },
  {
    value: 'joined',
    title: '系统执法',
    subtitle: '法官入局模式，二期开放',
  },
]

export const judgeSupportMeta = {
  full: {
    label: '完整支持',
    description: '板子已预留无主持人扩展能力',
  },
  partial: {
    label: '部分支持',
    description: '后续系统执法需要补充规则确认',
  },
  manual_only: {
    label: '主持人模式',
    description: '当前仅支持法官不入局主持',
  },
}

export const judgeRoomStatusMeta = {
  lobby: '大厅',
  playing: '对局中',
  finished: '已结束',
}

export const judgePhaseMeta = {
  lobby: '等待开局',
  night_action: '夜间',
  day_speech: '发言',
  exile_vote: '放逐投票',
  result: '结果',
}

export const judgeAdvanceActions = [
  { value: 'broadcast', label: '发公告' },
  { value: 'start_vote', label: '开启投票' },
  { value: 'resolve_night', label: '结算夜晚' },
  { value: 'resolve_vote', label: '结算投票' },
  { value: 'finish', label: '结束对局' },
]

export function getJudgeSupportMeta(level) {
  return judgeSupportMeta[level] || judgeSupportMeta.manual_only
}

export function getJudgeRoomStatusLabel(status) {
  return judgeRoomStatusMeta[status] || '房间'
}

export function getJudgePhaseLabel(phase) {
  return judgePhaseMeta[phase] || '进行中'
}
