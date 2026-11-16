// 有法官模式的上帝视角（纯函数，有单测）：座位身份角标、今晚汇总、控场按钮、暂停遮罩。
// 身份和汇总只有法官（judgeViewer）拿得到；控场按钮法官和无法官房主有；暂停遮罩所有人同一张。

import { isWolfTeam } from './night-order.js'

// 用 App.vue 里的阵营色变量（视图把它写进 style，小程序和 H5 都认 var()）
const CAMP_COLORS = { wolf: 'var(--camp-wolf)', god: 'var(--camp-god)', villager: 'var(--camp-villager)', third: 'var(--camp-third)' }

/** 阵营：wolf / third / villager / god（好人里平民之外都算神职）。 */
export function campOf(player) {
  if (player?.faction === '狼人') return 'wolf'
  if (player?.faction && player.faction !== '好人') return 'third'
  return player?.roleName === '平民' ? 'villager' : 'god'
}

function tonight(snapshot) {
  const day = snapshot?.currentDay || 1
  return (snapshot?.nightActions || []).filter((action) => (action.dayNo || day) === day)
}

/** 最后一次提交为准（后端每人每晚只认最后一次）。 */
function latestBySeat(actions) {
  const bySeat = new Map()
  for (const action of actions) {
    if (action.actorSeatNo) bySeat.set(action.actorSeatNo, action)
  }
  return bySeat
}

const ACTION_ALIASES = { 刀人: 'kill', 解药: 'save', 毒药: 'poison', 查验: 'check', 守护: 'guard', 空刀: 'none' }

function actionCode(action) {
  const type = action?.actionType || ''
  return ACTION_ALIASES[type] || type
}

export function godSeats(snapshot) {
  const seats = new Map()
  if (!snapshot?.judgeViewer) return seats
  const votes = new Map()
  if (snapshot.currentPhase === 'night_action') {
    for (const action of knivesBySeat(snapshot).values()) {
      if (actionCode(action) === 'kill' && action.targetSeatNo) votes.set(action.targetSeatNo, (votes.get(action.targetSeatNo) || 0) + 1)
    }
  }
  for (const player of snapshot.players || []) {
    if (!player?.seatNo) continue
    seats.set(player.seatNo, {
      roleShort: (player.roleName || '').slice(0, 1),
      roleColor: CAMP_COLORS[campOf(player)],
      votes: votes.get(player.seatNo) || 0,
    })
  }
  return seats
}

// 没配夜间顺序时的通用顺序（和后端机制优先级一致）；用来判断某个角色「还没轮到」还是「没有行动」。
const ROLE_ORDER = { 机械狼: 0.5, 守卫: 1, 狼人: 2, 狼美人: 2.5, 女巫: 3, 预言家: 4, 通灵师: 4, 猎人: 5, 白痴: 6, 骑士: 6 }
const KIND_ORDER = { mech_wolf: 0.5, guard: 1, wolf: 2, charm: 2.5, witch: 3, seer: 4, psychic: 4, hunter: 5, idle: 6 }
const VERBS = { save: '救', poison: '毒', check: '查验', guard: '守', kill: '刀', kill_pierce: '破盾刀', learn: '学习', charm: '魅惑' }

// 跟狼队一起刀人的机制（狼美人刀完还有自己的魅惑一步；机械狼单独一步，不在里面）
const WOLF_TEAM_MECHANICS = new Set(['wolf', 'wolf_king', 'wolf_beauty'])

/** 跟狼队一起睁眼的座位：有机制看机制（机械狼单独一步），没有按阵营。 */
function inWolfTeam(player) {
  if (player?.mechanic) return WOLF_TEAM_MECHANICS.has(player.mechanic)
  return player?.faction === '狼人'
}

/** 每个座位今晚最后一次刀人 / 空刀（狼美人之后的魅惑不算进刀口）。 */
function knivesBySeat(snapshot) {
  return latestBySeat(tonight(snapshot).filter((action) => actionCode(action) !== 'charm'))
}

/** 每个座位今晚的全部行动（机械狼一次可以交几件事）。 */
function allBySeat(actions) {
  const bySeat = new Map()
  for (const action of actions) {
    if (!action.actorSeatNo) continue
    if (!bySeat.has(action.actorSeatNo)) bySeat.set(action.actorSeatNo, [])
    bySeat.get(action.actorSeatNo).push(action)
  }
  return bySeat
}

function knifeText(bySeat, wolves) {
  const counts = new Map()
  for (const seat of wolves) {
    const action = bySeat.get(seat)
    if (action && actionCode(action) === 'kill' && action.targetSeatNo) counts.set(action.targetSeatNo, (counts.get(action.targetSeatNo) || 0) + 1)
  }
  if (!wolves.some((seat) => bySeat.has(seat))) return '还没定'
  if (!counts.size) return '空刀'
  const top = Math.max(...counts.values())
  const leaders = [...counts.entries()].filter(([, count]) => count === top)
  return leaders.length > 1 ? '平票 · 随机' : `${leaders[0][0]} 号`
}

/**
 * 角色在今晚的先后：板子配了 nightOrder 按它排（狼队各角色都算「狼人」那一步），没配按通用顺序。
 * 返回 { orderOf(roleName), current }，排不上的角色是 99（夜里不睁眼）。
 */
function nightRanking(snapshot, board) {
  const configured = board?.ruleDetail?.nightOrder || []
  if (!configured.length) {
    return { orderOf: (role) => ROLE_ORDER[role] || 99, current: KIND_ORDER[snapshot?.currentStep?.kind] || 0 }
  }
  const ranks = new Map()
  for (const step of configured) {
    const name = isWolfTeam(step) ? '狼人' : step.roleName
    if (name && !ranks.has(name)) ranks.set(name, ranks.size + 1)
  }
  // 狼美人在板子顺序里并进了狼人那一步，她的魅惑紧跟在后面
  if (ranks.has('狼人') && !ranks.has('狼美人')) ranks.set('狼美人', ranks.get('狼人') + 0.5)
  const step = snapshot?.currentStep
  const currentName = step?.kind === 'wolf' ? '狼人' : step?.roleName
  return { orderOf: (role) => ranks.get(role) || 99, current: ranks.get(currentName) || 0 }
}

/** 今晚每个角色做了什么：「刀口 5 号 · 1→5 2→?」（刀口在前，折行只折在刀的人之间）「救 5 号」「还没轮到」。 */
export function tonightRows(snapshot, board = null) {
  if (!snapshot?.judgeViewer) return []
  const bySeat = latestBySeat(tonight(snapshot))
  const knives = knivesBySeat(snapshot)
  const alive = (snapshot.players || []).filter((player) => player?.seatNo && player.alive !== false)
  const ranking = nightRanking(snapshot, board)
  const currentOrder = ranking.current
  const rows = []
  const waiting = (role) => {
    const order = ranking.orderOf(role)
    return order > currentOrder ? '还没轮到' : order === currentOrder ? '行动中' : '没有行动'
  }

  const wolves = alive.filter(inWolfTeam).map((player) => player.seatNo).sort((a, b) => a - b)
  if (wolves.length) {
    const picks = wolves.map((seat) => {
      const action = knives.get(seat)
      if (!action) return `${seat}→?`
      return actionCode(action) === 'kill' && action.targetSeatNo ? `${seat}→${action.targetSeatNo}` : `${seat}→空刀`
    })
    rows.push({ role: '狼人', seats: wolves, state: `刀口 ${knifeText(knives, wolves)} · ${picks.join(' ')}` })
  }

  // 狼美人刀完单独一行魅惑（只写魅惑，她的刀已经在狼人那一行）
  const everything = allBySeat(tonight(snapshot))
  const beauties = alive.filter((player) => player.mechanic === 'wolf_beauty')
  if (beauties.length) {
    const seats = beauties.map((player) => player.seatNo).sort((a, b) => a - b)
    const charms = seats.flatMap((seat) => everything.get(seat) || []).filter((action) => actionCode(action) === 'charm' && action.targetSeatNo)
    const role = beauties[0].roleName || '狼美人'
    rows.push({ role, seats, state: charms.length ? charms.map((action) => `魅惑 ${action.targetSeatNo} 号`).join(' · ') : waiting(role) })
  }

  const byRole = new Map()
  for (const player of alive) {
    if (inWolfTeam(player)) continue
    if (ranking.orderOf(player.roleName) === 99 && !bySeat.has(player.seatNo)) continue
    if (!byRole.has(player.roleName)) byRole.set(player.roleName, [])
    byRole.get(player.roleName).push(player.seatNo)
  }
  for (const [role, seats] of byRole) {
    const done = seats.flatMap((seat) => everything.get(seat) || [])
    let state
    if (done.length) {
      state = done
        .map((action) => {
          const code = actionCode(action)
          if (code === 'none') return '不行动'
          if (code === 'confirm') return '已确认'
          return VERBS[code] && action.targetSeatNo ? `${VERBS[code]} ${action.targetSeatNo} 号` : '已行动'
        })
        .join(' · ')
    } else {
      state = waiting(role)
    }
    rows.push({ role, seats, state })
  }
  return rows
}

/**
 * 控场按钮（用户给定）：有法官时法官有暂停 / 继续、点杀、提前结束；无法官房主只有暂停 / 继续和提前结束（记流局）。
 * 只在对局中。
 */
export function judgeActions(snapshot) {
  const none = { pause: false, resume: false, forceOut: false, finish: false }
  if (snapshot?.roomStatus !== 'playing') return none
  const judge = Boolean(snapshot.judgeViewer)
  const joinedHost = snapshot.judgeMode === 'joined' && Boolean(snapshot.host)
  if (!judge && !joinedHost) return none
  const paused = Boolean(snapshot.paused)
  return { pause: !paused, resume: paused, forceOut: judge, finish: true }
}

/** 暂停遮罩：所有人同一张；能继续的人（法官、无法官房主）遮罩上有「继续游戏」（遮罩盖住了顶栏）。 */
export function pausedOverlay(snapshot) {
  if (!snapshot?.paused || snapshot.roomStatus !== 'playing') return null
  return {
    title: '对局暂停',
    hint: snapshot.judgeMode === 'joined' ? '游戏已暂停，等房主继续' : '游戏已暂停，等法官继续',
    canResume: judgeActions(snapshot).resume,
  }
}

/**
 * 房主右上方的设置菜单（用户给定：无法官房主的暂停游戏、提前结束不占底部，放进这里）。
 * 无法官房主：暂停 / 继续游戏、提前结束；法官：提前结束（暂停在底部，点杀在点座位的菜单里）。
 * 声音开关不进菜单，还是顶栏上的「声音开 / 关」（管播报和夜晚背景音乐；用户给定）。只在对局中有。
 */
export function hostMenu(snapshot) {
  if (!snapshot?.host) return []
  const items = []
  const controls = judgeActions(snapshot)
  if (!snapshot.judgeViewer && controls.finish) {
    items.push(controls.resume ? { key: 'resume', label: '继续游戏' } : { key: 'pause', label: '暂停游戏' })
    items.push({ key: 'finish', label: '提前结束' })
  } else if (snapshot.judgeViewer && controls.finish) {
    // 法官的暂停在底部操作栏，点杀在点座位的菜单里，提前结束放这里
    items.push({ key: 'finish', label: '提前结束' })
  }
  return items
}
