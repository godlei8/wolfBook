// 白天：发言、放逐投票、票型公布、房主操作栏（纯函数，有单测）。
// 投票进度只显示投了几个人，不显示投给谁；房主的按钮只带 action，不带出局者 / 胜方 / 发言顺序（新流程按规则结算，带了后端会拒）。
import { speakerOf } from './sheriff.js'

function aliveSelf(snapshot) {
  const self = snapshot?.selfPlayer
  return Boolean(!snapshot?.judgeViewer && self?.seatNo && self.alive !== false)
}

export function speechPanel(snapshot) {
  const order = snapshot?.speech?.order || []
  const index = snapshot?.speech?.index || 0
  const speakerSeat = speakerOf(snapshot)
  return {
    order,
    index,
    speakerSeat,
    isSpeaker: Boolean(speakerSeat) && aliveSelf(snapshot) && snapshot.selfPlayer.seatNo === speakerSeat,
    sequence: order.map((seatNo, i) => ({ seatNo, state: i < index ? 'done' : i === index ? 'current' : 'next' })),
  }
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
  sheriff_speech: [['next_speaker', '下一位'], ['start_sheriff_vote', '开始投票']],
  sheriff_vote: [['resolve_sheriff_vote', '结算']],
  sheriff_pk: [['resolve_sheriff_vote', '结算']],
  day_announce: [['start_speech', '开始发言']],
  day_speech: [['next_speaker', '跳过这位'], ['start_vote', '发起投票']],
  exile_vote: [['resolve_vote', '结算投票']],
  vote_pk: [['resolve_vote', '结算 PK']],
  vote_result: [['continue', '继续']],
}

/** 房主的白天操作栏。无法官房主多一个「强制结束」（记流局，二次确认）；有法官时不能流局。暂停中全部不可点。 */
export function hostActions(snapshot) {
  if (!snapshot?.host || snapshot.roomStatus !== 'playing') return []
  const items = (PHASE_ACTIONS[snapshot.currentPhase] || []).map(([action, label]) => ({
    action,
    label,
    disabled: Boolean(snapshot.paused),
    confirm: false,
    payload: { action },
  }))
  if (items.length && snapshot.judgeMode === 'joined') {
    items.push({ action: 'finish', label: '强制结束', disabled: Boolean(snapshot.paused), confirm: true, payload: { action: 'finish' } })
  }
  return items
}
