// 房主手机的播报台词（纯函数，有单测）。只有房主手机出声（设计稿 §6）；本期用 WechatSI 合成，2C 换成预生成片段。
// 每条台词有 key（房间 + 天 + 阶段 + 步骤 / 发言人），同一个 key 只播一次；房间页把播过的 key 记进本地存储，切后台回来不重播。
// 夜里只用步骤的 roleName 和 kind，不依赖 currentStep.line（房主不一定是这一步的持有者）。
import { announcePanel } from './sheriff.js'
import { speakerOf } from './sheriff.js'
import { countdown } from './room-view.js'
import { voteResultPanel } from './day.js'
import { verdictText } from './rounds.js'

const NIGHT_HINTS = {
  wolf: '请选择今晚要刀的人',
  witch: '请看手机上的刀口，决定用不用药',
  seer: '请选择要查验的人',
  guard: '请选择今晚要守护的人',
  hunter: '请确认你的开枪状态',
}

const MAX_REMEMBERED = 30

function cue(snapshot, suffix, lines) {
  const filtered = lines.filter(Boolean)
  if (!filtered.length) return null
  return { key: `${snapshot.roomId}:${snapshot.currentDay || 1}:${suffix}`, lines: filtered }
}

function remaining(snapshot, now) {
  if (!snapshot.phaseDeadline) return null
  return countdown(snapshot.phaseDeadline, now, false).seconds
}

function nightCue(snapshot, now) {
  const step = snapshot.currentStep
  if (!step) return null
  const left = remaining(snapshot, now)
  if (left !== null && left > 0 && left <= 10) return cue(snapshot, `night:${step.index}:warn`, ['请尽快行动'])
  const open = `${step.roleName}请睁眼，${NIGHT_HINTS[step.kind] || '请行动'}`
  return cue(snapshot, `night:${step.index}`, step.index === 0 ? ['天黑请闭眼', open] : [open])
}

function dayCue(snapshot, now) {
  const phase = snapshot.currentPhase
  switch (phase) {
    case 'sheriff_signup':
      return cue(snapshot, phase, ['天亮了', '现在开始上警，想竞选警长的请在手机上报名'])
    case 'sheriff_speech': {
      const speaker = speakerOf(snapshot)
      return cue(snapshot, `${phase}:${snapshot.speech?.index || 0}`, [speaker ? `请 ${speaker} 号警上发言` : '警上发言'])
    }
    case 'sheriff_vote':
      return cue(snapshot, phase, ['请警下玩家投票选警长'])
    case 'sheriff_pk':
      return cue(snapshot, phase, ['警长平票，进入 PK 投票'])
    case 'day_announce': {
      const panel = announcePanel(snapshot)
      const round = (snapshot.rounds || []).find((item) => item.dayNo === (snapshot.currentDay || 1))
      // 当天上过警的话「天亮了」已经在上警前播过
      const opening = round?.sheriff ? (snapshot.sheriffSeatNo ? `${snapshot.sheriffSeatNo} 号当选警长` : '警徽流失') : '天亮了'
      return cue(snapshot, phase, [opening, panel.text])
    }
    case 'day_speech': {
      const speaker = speakerOf(snapshot)
      const index = snapshot.speech?.index || 0
      const left = remaining(snapshot, now)
      if (speaker && left !== null && left > 0 && left <= 30) return cue(snapshot, `speech:${index}:30`, ['发言还剩 30 秒'])
      return cue(snapshot, `speech:${index}`, [speaker ? `请 ${speaker} 号发言` : ''])
    }
    case 'exile_vote':
      return cue(snapshot, phase, ['发言结束，请投票'])
    case 'vote_pk':
      return cue(snapshot, phase, ['平票，进入 PK 投票'])
    case 'vote_result': {
      const result = voteResultPanel(snapshot)
      let line = '没有人出局'
      if (result.revealedIdiot) line = `${result.revealedIdiot} 号翻牌是白痴，免于出局`
      else if (result.eliminatedSeat) line = `${result.eliminatedSeat} 号被放逐`
      else if (result.pkSeats.length) line = `${result.pkSeats.join('、')} 号平票`
      return cue(snapshot, `${phase}:${(snapshot.rounds || []).length}`, [line])
    }
    case 'skill_pending':
      return cue(snapshot, `pending:${snapshot.pending?.kind || ''}:${snapshot.pending?.seatNo || ''}`, ['请出局的玩家在手机上确认'])
    default:
      return null
  }
}

export function voiceCue(snapshot, now = Date.now()) {
  if (!snapshot?.host || snapshot.paused) return null
  if (snapshot.roomStatus === 'finished') {
    const verdict = verdictText(snapshot)
    return { key: `${snapshot.roomId}:finished`, lines: ['游戏结束', verdict.reason || verdict.title] }
  }
  if (snapshot.roomStatus !== 'playing') return null
  return snapshot.currentPhase === 'night_action' ? nightCue(snapshot, now) : dayCue(snapshot, now)
}

export function shouldPlay(next, playedKeys) {
  return Boolean(next) && !(playedKeys || []).includes(next.key)
}

/** 记住播过的 key（只留最近 30 条，存本地）。 */
export function rememberCue(playedKeys, played) {
  const list = (playedKeys || []).filter((key) => key !== played.key)
  list.push(played.key)
  return list.slice(-MAX_REMEMBERED)
}
