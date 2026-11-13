// 白天：发言、放逐投票、票型公布、法官的推进按钮（纯函数，有单测）。
// 投票进度只显示投了几个人，不显示投给谁；法官的按钮只带 action，不带出局者 / 胜方 / 发言顺序（新流程按规则结算，带了后端会拒）。
import { speakerOf } from './sheriff.js'

function aliveSelf(snapshot) {
  const self = snapshot?.selfPlayer
  return Boolean(!snapshot?.judgeViewer && self?.seatNo && self.alive !== false)
}

const DIRECTION_TEXT = { left: '警左', right: '警右', dead_left: '死左', dead_right: '死右' }

/** 白天发言：不列整个顺序（用户给定），只提示方向；后端随机起点时（direction 为空）说从几号开始。 */
export function speechPanel(snapshot) {
  const order = snapshot?.speech?.order || []
  const index = snapshot?.speech?.index || 0
  const speakerSeat = speakerOf(snapshot)
  const direction = snapshot?.speech?.direction
  return {
    speakerSeat,
    nextSeat: order[index + 1] || null,
    directionText: snapshot?.speech?.pk ? 'PK 发言' : DIRECTION_TEXT[direction] || (order.length ? `从 ${order[0]} 号开始` : ''),
    isSpeaker: Boolean(speakerSeat) && aliveSelf(snapshot) && snapshot.selfPlayer.seatNo === speakerSeat,
    // 发言时长只有 30 秒时一开始就剩 30 秒，那就只在最后 10 秒变红
    warnAt: Number(snapshot?.rules?.speechSeconds || 120) > 30 ? 30 : 10,
  }
}

function cannotVoteReason(snapshot) {
  if (snapshot?.judgeViewer) return '法官不投票'
  if (snapshot?.selfPlayer?.seatNo && snapshot.selfPlayer.alive === false) return '你已出局，不能投票'
  return '你这一轮不能投票'
}

export function votePanel(snapshot) {
  const stage = snapshot?.currentPhase === 'vote_pk' ? 'pk' : 'exile'
  const info = snapshot?.dayInfo || {}
  const targets = stage === 'pk'
    ? [...(snapshot?.pkSeats || [])]
    : (snapshot?.players || []).filter((player) => player?.seatNo && player.alive !== false).map((player) => player.seatNo).sort((a, b) => a - b)
  let mine = ''
  if (info.voted) mine = info.myVote ? `你投了 ${info.myVote} 号` : '你弃票了'
  return {
    stage,
    canVote: aliveSelf(snapshot) && Boolean(info.canVote),
    reason: cannotVoteReason(snapshot),
    targets,
    abstainAllowed: true,
    voted: Boolean(info.voted),
    myVote: info.myVote ?? null,
    mine,
    progress: `已投 ${info.votedCount || 0} / ${info.voterCount || 0}`,
  }
}

/** 票型来自对局记录当天最后一轮放逐（带警长 1.5 票的加权）。 */
export function voteResultPanel(snapshot) {
  const day = snapshot?.currentDay || 1
  const round = (snapshot?.rounds || []).find((item) => item.dayNo === day)
  const exiles = round?.exile || []
  const last = exiles[exiles.length - 1] || null
  const idiot = (round?.skills || []).find((skill) => skill.kind === 'idiot_revealed')
  return {
    tallies: (last?.tallies || []).map((tally) => ({
      targetSeatNo: tally.targetSeatNo,
      weight: tally.weight,
      voters: (tally.voterSeatNos || []).join('、'),
    })),
    abstain: (last?.abstainSeatNos || []).join('、'),
    eliminatedSeat: last?.eliminatedSeatNo ?? null,
    revealedIdiot: idiot ? idiot.actorSeatNo : null,
    pkSeats: snapshot?.pkSeats?.length ? [...snapshot.pkSeats] : last?.pkSeatNos || [],
  }
}

const PHASE_ACTIONS = {
  sheriff_signup: [['close_sheriff_signup', '截止报名']],
  sheriff_speech: [['next_speaker', '跳过发言'], ['start_sheriff_vote', '开始投票']],
  sheriff_vote: [['resolve_sheriff_vote', '结算']],
  sheriff_pk: [['resolve_sheriff_vote', '结算']],
  day_announce: [['start_speech', '开始发言']],
  // 所有存活玩家发完言自动进放逐投票（通用流程 6），法官只能跳过离席的人
  day_speech: [['next_speaker', '跳过发言']],
  exile_vote: [['resolve_vote', '结算投票']],
  vote_pk: [['resolve_vote', '结算 PK']],
  vote_result: [['continue', '继续']],
}

/**
 * 法官的白天推进按钮（用户给定：每一步都按倒计时自动走，法官可以不等倒计时提前推进）。
 * 无法官房主没有推进按钮（暂停和提前结束在 judge-bar.js 的 judgeActions）。暂停中全部不可点。
 */
export function hostActions(snapshot) {
  if (!snapshot?.judgeViewer || snapshot.roomStatus !== 'playing') return []
  // 警上自爆那天公布完死讯直接天黑
  const phaseActions = snapshot.currentPhase === 'day_announce' && snapshot.nightNext
    ? [['start_night', '天黑']]
    : PHASE_ACTIONS[snapshot.currentPhase] || []
  const items = phaseActions.map(([action, label]) => ({
    action,
    label,
    disabled: Boolean(snapshot.paused),
    confirm: false,
    payload: { action },
  }))
  return items
}
