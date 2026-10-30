// 有法官模式的上帝视角（纯函数，有单测）：座位身份角标、今晚汇总、暂停 / 点杀按钮、暂停遮罩。
// 只有法官（judgeViewer）拿得到这些；暂停遮罩所有人同一张。

const CAMP_COLORS = { wolf: '#ff7468', god: '#e9c46a', villager: '#8fd0a6', third: '#f7a8cb' }

function campOf(player) {
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
    for (const action of latestBySeat(tonight(snapshot)).values()) {
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
const ROLE_ORDER = { 守卫: 1, 狼人: 2, 女巫: 3, 预言家: 4, 猎人: 5 }
const KIND_ORDER = { guard: 1, wolf: 2, witch: 3, seer: 4, hunter: 5 }
const VERBS = { save: '救', poison: '毒', check: '查验', guard: '守', kill: '刀' }

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

/** 今晚每个角色做了什么：「1→5 2→? · 刀口 5 号」「救 5 号」「还没轮到」。 */
export function tonightRows(snapshot) {
  if (!snapshot?.judgeViewer) return []
  const bySeat = latestBySeat(tonight(snapshot))
  const alive = (snapshot.players || []).filter((player) => player?.seatNo && player.alive !== false)
  const currentOrder = KIND_ORDER[snapshot.currentStep?.kind] || 0
  const rows = []

  const wolves = alive.filter((player) => player.faction === '狼人').map((player) => player.seatNo).sort((a, b) => a - b)
  if (wolves.length) {
    const picks = wolves.map((seat) => {
      const action = bySeat.get(seat)
      if (!action) return `${seat}→?`
      return actionCode(action) === 'kill' && action.targetSeatNo ? `${seat}→${action.targetSeatNo}` : `${seat}→空刀`
    })
    rows.push({ role: '狼人', seats: wolves, state: `${picks.join(' ')} · 刀口 ${knifeText(bySeat, wolves)}` })
  }

  const byRole = new Map()
  for (const player of alive) {
    if (player.faction === '狼人') continue
    if (!ROLE_ORDER[player.roleName] && !bySeat.has(player.seatNo)) continue
    if (!byRole.has(player.roleName)) byRole.set(player.roleName, [])
    byRole.get(player.roleName).push(player.seatNo)
  }
  for (const [role, seats] of byRole) {
    const done = seats.map((seat) => bySeat.get(seat)).filter(Boolean)
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
      const order = ROLE_ORDER[role] || 99
      state = order > currentOrder ? '还没轮到' : order === currentOrder ? '行动中' : '没有行动'
    }
    rows.push({ role, seats, state })
  }
  return rows
}

export function judgeActions(snapshot) {
  if (!snapshot?.judgeViewer || snapshot.roomStatus !== 'playing') return { pause: false, resume: false, forceOut: false }
  return snapshot.paused ? { pause: false, resume: true, forceOut: true } : { pause: true, resume: false, forceOut: true }
}

export function pausedOverlay(snapshot) {
  if (!snapshot?.paused || snapshot.roomStatus !== 'playing') return null
  return { title: '法官暂停', hint: '计时已停，等法官继续' }
}
