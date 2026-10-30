// 法官房间页的视图模型（纯函数，有单测）：该显示哪个视图、两侧座位怎么排、倒计时、阶段标题。
// 零场外：这里的结果只看房间状态，不看查看者是谁——夜里所有人的标题、倒计时、座位排法都一样。

const SHERIFF_PHASES = ['sheriff_signup', 'sheriff_speech', 'sheriff_vote', 'sheriff_pk']

export function pickView(snapshot) {
  if (!snapshot) return 'loading'
  if (snapshot.roomStatus === 'lobby') return 'lobby'
  if (snapshot.roomStatus === 'finished') return 'result'
  const phase = snapshot.currentPhase
  if (phase === 'night_action') return 'night'
  if (phase === 'skill_pending') return 'pending'
  if (SHERIFF_PHASES.includes(phase)) return 'sheriff'
  if (phase === 'day_announce') return 'announce'
  if (phase === 'day_speech') return 'speech'
  if (phase === 'exile_vote' || phase === 'vote_pk') return 'vote'
  if (phase === 'vote_result') return 'voteResult'
  return 'speech'
}

/**
 * 座位分两列：左 1..⌈N/2⌉，右其余。每侧 ≤ 6 人用 92rpx 头像，再多用 68rpx。
 * cardHeight 是中间立绘的高度，正好和一侧座位列对齐。空座位也占位。
 */
export function seatColumns(players, playerCount) {
  const total = Math.max(0, Number(playerCount) || 0)
  const bySeat = new Map()
  for (const player of players || []) {
    if (player?.seatNo) bySeat.set(player.seatNo, player)
  }
  const seats = Array.from({ length: total }, (_, i) => {
    const player = bySeat.get(i + 1) || null
    return { seatNo: i + 1, player, empty: !player }
  })
  const perSide = Math.ceil(total / 2)
  const compact = perSide > 6
  const size = compact ? 68 : 92
  const gap = compact ? 16 : 20
  return {
    left: seats.slice(0, perSide),
    right: seats.slice(perSide),
    size,
    gap,
    cardHeight: perSide > 0 ? perSide * size + (perSide - 1) * gap : 0,
  }
}

/** 后端下发的 LocalDateTime 没带时区，是北京时间；手动解析（真机 JSCore 对 9 位小数秒不一定认）。 */
export function parseServerTime(text) {
  if (!text) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?/.exec(String(text))
  if (!match) return null
  const [, y, mo, d, h, mi, s = '0', frac = '0'] = match
  const ms = Number(frac.slice(0, 3).padEnd(3, '0'))
  return Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h) - 8, Number(mi), Number(s), ms)
}

export function countdown(deadline, now, paused) {
  const end = parseServerTime(deadline)
  if (end === null) return { seconds: 0, text: '', warn: false }
  const seconds = Math.max(0, Math.floor((end - now) / 1000))
  if (paused) return { seconds, text: '暂停', warn: false }
  const text = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
  return { seconds, text, warn: seconds <= 10 }
}

const DAY_TITLES = {
  sheriff_signup: '上警报名',
  sheriff_speech: '警上发言',
  sheriff_vote: '警长投票',
  sheriff_pk: '警长 PK',
  day_announce: '天亮了',
  day_speech: '发言',
  exile_vote: '放逐投票',
  vote_pk: 'PK 投票',
  vote_result: '票型公布',
  skill_pending: '出局技能',
}

/** 夜里只用 currentStep.roleName，不看查看者身份。 */
export function phaseTitle(snapshot) {
  if (!snapshot) return ''
  if (snapshot.roomStatus === 'lobby') return '等待入座'
  if (snapshot.roomStatus === 'finished') return '对局结束'
  const day = snapshot.currentDay || 1
  if (snapshot.currentPhase === 'night_action') {
    const roleName = snapshot.currentStep?.roleName
    return roleName ? `第 ${day} 夜 · ${roleName}请睁眼` : `第 ${day} 夜 · 天黑请闭眼`
  }
  return `第 ${day} 天 · ${DAY_TITLES[snapshot.currentPhase] || '进行中'}`
}
