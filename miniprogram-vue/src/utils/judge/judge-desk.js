// 法官视角（纯函数，有单测 tests/judge-desk.test.mjs）。用户给定：以玩家的牌桌为主，在上面叠加法官的功能——
// 座位上的标记（夜里谁刀谁、刀口、谁在行动；白天整个发言顺序；投票时谁投了谁、得几票、谁还没投）、
// 面板（狼刀没统一的提示、昨晚复盘、实时票型）、点座位的菜单（他的经历、点杀、跳过发言）。
// 只有法官（judgeViewer）拿得到；玩家快照里本来就没有这些数据，这里也一律返回空。
import { gameLog } from './game-log.js'

function tonight(snapshot) {
  const day = snapshot?.currentDay || 1
  return (snapshot?.nightActions || []).filter((action) => (action.dayNo || day) === day)
}

const ALIASES = { 刀人: 'kill', 空刀: 'none' }
const actionOf = (action) => ALIASES[action?.actionType] || action?.action || action?.actionType || ''

/** 跟狼队一起刀人的座位：有机制看机制（机械狼单独一步），没有按阵营。 */
function inWolfTeam(player) {
  if (player?.mechanic) return player.mechanic === 'wolf' || player.mechanic === 'wolf_king'
  return player?.faction === '狼人'
}

function alivePlayers(snapshot) {
  return (snapshot?.players || []).filter((player) => player?.seatNo && player.alive !== false && player.playing !== false)
}

/** 今晚每只活狼最后一次刀的人，和按多数定的刀口（平票为 null，带上平票的座位）。 */
function knifePicks(snapshot) {
  const wolves = new Set(alivePlayers(snapshot).filter(inWolfTeam).map((player) => player.seatNo))
  const picks = new Map()
  for (const action of tonight(snapshot)) {
    if (!wolves.has(action.actorSeatNo)) continue
    if (actionOf(action) === 'kill' && action.targetSeatNo) picks.set(action.actorSeatNo, action.targetSeatNo)
    else if (actionOf(action) === 'none') picks.delete(action.actorSeatNo)
  }
  const counts = new Map()
  for (const target of picks.values()) counts.set(target, (counts.get(target) || 0) + 1)
  const top = counts.size ? Math.max(...counts.values()) : 0
  const leaders = [...counts.entries()].filter(([, count]) => count === top).map(([seat]) => seat).sort((a, b) => a - b)
  return { picks, counts, target: leaders.length === 1 ? leaders[0] : null, tied: leaders.length > 1 ? leaders : [] }
}

/** 这一步的持有者：狼人那一步是所有活狼，其余按角色名。 */
function stepHolders(snapshot) {
  const step = snapshot?.currentStep
  if (!step) return []
  return alivePlayers(snapshot)
    .filter((player) => (step.kind === 'wolf' ? inWolfTeam(player) : player.roleName === step.roleName))
    .map((player) => player.seatNo)
}

/** 每个目标的得票（警长 1.5 票），按票数从高到低。 */
function weightedTallies(snapshot) {
  const sheriff = snapshot?.sheriffSeatNo || 0
  return (snapshot?.voteTallies || [])
    .map((tally) => ({
      seatNo: tally.targetSeatNo,
      voters: tally.voterSeatNos || [],
      weight: (tally.voterSeatNos || []).reduce((sum, voter) => sum + (voter === sheriff ? 1.5 : 1), 0),
    }))
    .filter((tally) => tally.seatNo && tally.voters.length)
    .sort((a, b) => b.weight - a.weight || a.seatNo - b.seatNo)
}

/** 还没投（或弃票）的人：活着、这一轮能投（PK 时平票的人不投）。 */
function waitingVoters(snapshot) {
  const voted = new Set((snapshot?.voteTallies || []).flatMap((tally) => tally.voterSeatNos || []))
  const pk = snapshot?.currentPhase === 'vote_pk' ? new Set(snapshot?.pkSeats || []) : new Set()
  return alivePlayers(snapshot)
    .filter((player) => player.canVote !== false && !voted.has(player.seatNo) && !pk.has(player.seatNo))
    .map((player) => player.seatNo)
    .sort((a, b) => a - b)
}

const VOTE_PHASES = ['exile_vote', 'vote_pk']

/**
 * 座位上的法官标记：Map(座位 → { tag, tagKind, count, countKind, ring })。
 * tag 在座位左上角（tagKind：dark / red / moon / done / gold），count 在头像中间（得票），
 * ring：act（这一步在行动的人）/ target（今晚的刀口）。
 */
export function judgeSeatMarks(snapshot, view) {
  const marks = new Map()
  if (!snapshot?.judgeViewer || snapshot.roomStatus !== 'playing') return marks
  const mark = (seat) => {
    if (!marks.has(seat)) marks.set(seat, { tag: '', tagKind: '', count: '', countKind: '', ring: '' })
    return marks.get(seat)
  }
  if (view === 'night') {
    const knife = knifePicks(snapshot)
    for (const [wolf, target] of knife.picks) Object.assign(mark(wolf), { tag: `刀${target}`, tagKind: 'dark' })
    for (const seat of stepHolders(snapshot)) mark(seat).ring = 'act'
    if (knife.target) Object.assign(mark(knife.target), { tag: '刀口', tagKind: 'red', ring: 'target' })
    return marks
  }
  if ((view === 'speech' || view === 'sheriff') && snapshot.speech?.order?.length
    && ['day_speech', 'sheriff_speech'].includes(snapshot.currentPhase)) {
    const index = snapshot.speech.index || 0
    snapshot.speech.order.forEach((seat, position) => {
      Object.assign(mark(seat), position < index ? { tag: '✓', tagKind: 'done' } : { tag: String(position + 1), tagKind: 'moon' })
    })
    return marks
  }
  if (view === 'vote' && VOTE_PHASES.includes(snapshot.currentPhase)) {
    const tallies = weightedTallies(snapshot)
    const top = tallies.length ? tallies[0].weight : 0
    for (const tally of tallies) {
      for (const voter of tally.voters) Object.assign(mark(voter), { tag: `→${tally.seatNo}`, tagKind: 'dark' })
      Object.assign(mark(tally.seatNo), { count: String(tally.weight), countKind: tally.weight === top ? 'red' : 'light' })
    }
    for (const seat of waitingVoters(snapshot)) Object.assign(mark(seat), { tag: '未投', tagKind: 'gold' })
  }
  return marks
}

/** 投票时法官面板：每个目标一行（几票、比例条、最高票），还有谁没投。不是法官返回 null。 */
export function judgeVotePanel(snapshot) {
  if (!snapshot?.judgeViewer) return null
  const tallies = weightedTallies(snapshot)
  const top = tallies.length ? tallies[0].weight : 0
  const roles = new Map((snapshot.players || []).map((player) => [player.seatNo, player.roleName || '']))
  return {
    rows: tallies.map((tally) => ({
      seatNo: tally.seatNo,
      label: `${tally.seatNo} 号 ${(roles.get(tally.seatNo) || '').slice(0, 2)}`,
      count: `${tally.weight} 票`,
      pct: top ? Math.round((tally.weight / top) * 100) : 0,
      top: tally.weight === top,
    })),
    waiting: waitingVoters(snapshot),
  }
}

/** 狼刀没统一时提醒法官会怎么结算（用户给定规则：按多数，平票随机）。 */
export function judgeNightAlert(snapshot) {
  if (!snapshot?.judgeViewer || snapshot.currentPhase !== 'night_action') return ''
  const knife = knifePicks(snapshot)
  if (knife.counts.size < 2) return ''
  if (knife.target) return `狼队没统一：按多数刀 ${knife.target} 号`
  return `狼队平票：到点在 ${knife.tied.join('、')} 号里随机`
}

/** 白天给法官的昨晚复盘（只有法官看得到）：「狼人 刀 5 号 · 预言家 验 3 号（狼人）」。 */
export function judgeRecap(snapshot) {
  if (!snapshot?.judgeViewer) return ''
  const day = snapshot.currentDay || 1
  const night = gameLog(snapshot).days.find((item) => item.dayNo === day)?.night
  const actions = night?.actions || []
  if (!actions.length) return '昨晚没人行动'
  return actions
    .map((action) => `${action.role} ${action.verb}${action.target ? ` ${action.target.seatNo} 号` : ''}${action.note ? `（${action.note}）` : ''}`)
    .join(' · ')
}

/** 法官点一个座位：标题（座位、昵称、身份、死活）、他的经历，能做的事（正在发言时跳过、点杀活着的人）。 */
export function judgeSeatSheet(snapshot, seatNo) {
  if (!snapshot?.judgeViewer) return null
  const player = (snapshot.players || []).find((item) => item.seatNo === seatNo)
  if (!player) return null
  const alive = player.alive !== false
  const story = gameLog(snapshot).people.find((item) => item.person.seatNo === seatNo)?.story || '还没有记录'
  const items = []
  const order = snapshot.speech?.order || []
  const speaking = ['day_speech', 'sheriff_speech'].includes(snapshot.currentPhase) && order[snapshot.speech?.index || 0] === seatNo
  if (snapshot.roomStatus === 'playing' && speaking) items.push({ key: 'skip', label: '跳过他的发言' })
  if (snapshot.roomStatus === 'playing' && alive) items.push({ key: 'forceOut', label: `点杀 ${seatNo} 号` })
  return {
    title: `${seatNo} 号 ${player.nickname || ''} · ${player.roleName || '未知'} · ${alive ? '存活' : '出局'}`,
    content: story,
    items,
  }
}
