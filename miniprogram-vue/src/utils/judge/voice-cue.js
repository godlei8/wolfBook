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
  psychic: '请选择要查验的人',
  mech_wolf: '请决定今晚的行动',
  charm: '请选择今晚要魅惑的人',
  idle: '请稍候',
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
  // 白天发言时有人自爆，入夜那句由公告「X号自爆，天黑请闭眼」代替
  const exploded = (snapshot.latestAnnouncement || '').includes('自爆') ? snapshot.latestAnnouncement : ''
  return cue(snapshot, `night:${step.index}`, step.index === 0 ? [exploded || '天黑请闭眼', open] : [open])
}

// 天亮公布死讯那一句：「天亮了 / X 号当选警长 / 警徽流失」+「昨晚 X 号出局 / 昨晚是平安夜」。
// 公布死讯那一步和天亮直接进遗言那一步用同一个 key，说过一次就不再说
function dawnCue(snapshot) {
  const panel = announcePanel(snapshot)
  const round = (snapshot.rounds || []).find((item) => item.dayNo === (snapshot.currentDay || 1))
  // 当天上过警的话「天亮了」已经在上警前播过
  const opening = round?.sheriff ? (snapshot.sheriffSeatNo ? `${snapshot.sheriffSeatNo} 号当选警长` : '警徽流失') : '天亮了'
  return cue(snapshot, 'day_announce', [opening, panel.text])
}

// 昨夜有人出局时，房间一天亮就直接进了遗言 / 出局技能，没经过「公布死讯」。
// 这时房主手机要先把天亮那一句说了（还没说过的话），再说「请 X 号留遗言」。
// 白天放逐之后的待办、警上自爆那天（公告由自爆那一句来说）不算
function dawnBeforePending(snapshot, playedKeys) {
  if (!Array.isArray(playedKeys) || snapshot.nightNext) return null
  const round = (snapshot.rounds || []).find((item) => item.dayNo === (snapshot.currentDay || 1))
  if (!round?.nightDeaths?.length || round.exile?.length) return null
  const dawn = dawnCue(snapshot)
  return dawn && !playedKeys.includes(dawn.key) ? dawn : null
}

function dayCue(snapshot, now, playedKeys) {
  const phase = snapshot.currentPhase
  switch (phase) {
    case 'sheriff_signup':
      return cue(snapshot, phase, ['天亮了', '现在开始上警，想竞选警长的请在手机上报名'])
    case 'sheriff_speech': {
      const speaker = speakerOf(snapshot)
      const index = snapshot.speech?.index || 0
      // 昨天警上有人自爆，今天一天亮就接着竞选（不经过上警报名），「天亮了」在这里说
      const resumed = (snapshot.currentDay || 1) > 1 && index === 0
      return cue(snapshot, `${phase}:${index}`, [
        resumed ? '天亮了' : '', resumed ? '昨天的警长竞选继续' : '', speaker ? `请 ${speaker} 号警上发言` : '警上发言',
      ])
    }
    case 'sheriff_vote':
      return cue(snapshot, phase, ['请警下玩家投票选警长'])
    case 'sheriff_pk':
      return cue(snapshot, phase, ['警长平票，进入 PK 投票'])
    case 'day_announce': {
      // 警上自爆：先报公告、等遗言，「天黑请闭眼」留给自动天黑之后夜里第一步
      if (snapshot.nightNext) return cue(snapshot, `${phase}:explode`, [snapshot.latestAnnouncement, '请出局的玩家发表遗言'])
      return dawnCue(snapshot)
    }
    case 'day_speech': {
      const speaker = speakerOf(snapshot)
      const index = snapshot.speech?.index || 0
      const left = remaining(snapshot, now)
      // 发言时长只有 30 秒时一开始就剩 30 秒，不提醒，免得把「请 X 号发言」顶掉
      const long = Number(snapshot.rules?.speechSeconds || 120) > 30
      if (speaker && long && left !== null && left > 0 && left <= 30) return cue(snapshot, `speech:${index}:30`, ['发言还剩 30 秒'])
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
      // 同一天可能有放逐和 PK 两次票型，按当天第几轮区分
      const today = (snapshot.rounds || []).find((item) => item.dayNo === (snapshot.currentDay || 1))
      return cue(snapshot, `${phase}:${today?.exile?.length || 0}`, [line])
    }
    case 'skill_pending': {
      const dawn = dawnBeforePending(snapshot, playedKeys)
      if (dawn) return dawn
      // 遗言公开：每条遗言轮到时播一次「请 X 号留遗言」
      if (snapshot.currentPending?.kind === 'last_words') {
        return cue(snapshot, `words:${snapshot.currentPending.seatNo}:${snapshot.phaseDeadline || ''}`, [`请 ${snapshot.currentPending.seatNo} 号留遗言`])
      }
      // 房主不是当事人时拿不到 pending；每条待办有自己的截止时间，用它区分同一天的几次待办
      return cue(snapshot, `pending:${snapshot.phaseDeadline || ''}`, ['请出局的玩家在手机上确认'])
    }
    default:
      return null
  }
}

/**
 * 现在该播哪一句。playedKeys 是已经播过的 key：天亮直接进遗言时，用它判断「天亮了」说过没有（不传就不判断）。
 * 入夜的第一句永远是「天黑请闭眼」（白天有人自爆时由「X号自爆，天黑请闭眼」代替）。
 */
export function voiceCue(snapshot, now = Date.now(), playedKeys) {
  if (!snapshot?.host || snapshot.paused) return null
  if (snapshot.roomStatus === 'finished') {
    const verdict = verdictText(snapshot)
    return { key: `${snapshot.roomId}:finished`, lines: ['游戏结束', verdict.reason || verdict.title] }
  }
  if (snapshot.roomStatus !== 'playing') return null
  return snapshot.currentPhase === 'night_action' ? nightCue(snapshot, now) : dayCue(snapshot, now, playedKeys)
}

/** 播放超时就放弃（来电、别的应用抢走音频时 onEnded 可能永远不来），不让播报整局卡住。 */
export function withTimeout(promise, ms) {
  let timer = null
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('timeout')), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
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

/**
 * 播报失败（合成出错、音频被抢）：第一次失败把 key 放开，下一秒重播一次；再失败就算了，免得每秒刷一次。
 * failures 是 { key: 失败次数 }，只放内存。
 */
export function retryCue(playedKeys, failed, failures = {}) {
  const count = (failures[failed.key] || 0) + 1
  const next = { ...failures, [failed.key]: count }
  if (count > 1) return { playedKeys, failures: next }
  return { playedKeys: (playedKeys || []).filter((key) => key !== failed.key), failures: next }
}
