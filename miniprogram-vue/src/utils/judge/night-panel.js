// 夜晚视图的纯逻辑（有单测）。零场外（设计稿 §4.4）：
// 夜里所有人同一张立绘屏，标题、倒计时、立绘、面板区高度、座位上的「出局」标记都只看房间状态；
// 只有这一步的持有者（活着、不是法官）多一块操作面板，狼队那一步狼人在座位上多看到队友和票数。

/** 快照里 currentStep.line 只发给这一步的持有者和法官。 */
export function isStepHolder(snapshot) {
  return Boolean(!snapshot?.judgeViewer && snapshot?.currentStep?.line && snapshot?.selfPlayer?.alive)
}

/** 当前角色的立绘：按步骤的角色名在板子阵容里找，没有就用卡背（返回空串）。 */
export function nightArt(snapshot, board) {
  const roleName = snapshot?.currentStep?.roleName
  if (!roleName) return ''
  const role = (board?.roles || []).find((item) => item?.name === roleName)
  return role?.fullIllustration || ''
}

const PANEL_HEIGHTS = { wolf: 380, witch: 380, seer: 300, guard: 300, hunter: 240, generic: 240 }

/** 面板区高度只由这一步的类型决定，所有人一样（非持有者渲染等高的空白占位）。 */
export function panelHeight(kind) {
  return PANEL_HEIGHTS[kind] || PANEL_HEIGHTS.generic
}

function seatText(seat) {
  return `${seat} 号`
}

/** 狼队刀口，规则同后端：取票数最多的目标，平票随机，没有人刀就是空刀。 */
function knifeOf(picks) {
  const counts = new Map()
  for (const pick of picks) {
    if (pick.decided && pick.targetSeatNo) counts.set(pick.targetSeatNo, (counts.get(pick.targetSeatNo) || 0) + 1)
  }
  if (!picks.some((pick) => pick.decided)) return { seat: null, text: '还没选' }
  if (!counts.size) return { seat: null, text: '空刀' }
  const top = Math.max(...counts.values())
  const leaders = [...counts.entries()].filter(([, count]) => count === top).map(([seat]) => seat)
  if (leaders.length > 1) return { seat: null, text: '平票 · 随机' }
  return { seat: leaders[0], text: `${seatText(leaders[0])} · ${top} 票` }
}

function targetButton(action, verb, selection, extra = {}) {
  return {
    action,
    label: selection ? `${verb} ${seatText(selection)}` : `选座位后${verb}`,
    target: selection || null,
    disabled: !selection,
    ...extra,
  }
}

const DONE_TEXT = {
  kill: (target) => `已选刀 ${seatText(target)}`,
  save: (target) => `已救 ${seatText(target)}`,
  poison: (target) => `已毒 ${seatText(target)}`,
  check: (target) => `已查验 ${seatText(target)}`,
  guard: (target) => `已守 ${seatText(target)}`,
  none: () => '已确认',
  confirm: () => '已确认',
}

function doneText(info) {
  const make = info?.myAction ? DONE_TEXT[info.myAction] : null
  return make ? make(info.myTarget) : ''
}

/**
 * 持有者的操作面板；不是持有者返回 null。selection 是持有者在两侧座位上点选的座位号（0 = 没选）。
 * 单人角色提交一次就定了（后端每人每晚只认最后一次提交，交完不再给按钮）；狼人可以改刀。
 */
export function nightPanel(snapshot, selection = 0) {
  if (!isStepHolder(snapshot)) return null
  const kind = snapshot.currentStep.kind
  const info = snapshot.nightInfo || {}
  const self = snapshot.selfPlayer
  const done = doneText(info)

  if (kind === 'wolf') {
    const picks = info.wolfPicks || []
    return {
      kind,
      rows: picks.map((pick) => ({
        seatNo: pick.seatNo,
        me: pick.seatNo === self.seatNo,
        text: !pick.decided ? '还没选' : pick.targetSeatNo ? seatText(pick.targetSeatNo) : '空刀',
      })),
      knife: knifeOf(picks),
      done,
      buttons: [{ action: 'none', label: '空刀', target: null, disabled: false }, targetButton('kill', '刀', selection)],
    }
  }

  if (kind === 'witch') {
    const knifeSeat = info.knifeSeat || null
    const antidote = info.antidote !== false
    const poison = info.poison !== false
    const rules = snapshot.rules || {}
    const selfSaveAllowed = (rules.witchSelfSave || 'first_night') === 'first_night' && (snapshot.currentDay || 1) === 1
    const canSave = antidote && Boolean(knifeSeat) && (knifeSeat !== self.seatNo || selfSaveAllowed)
    const buttons = []
    if (!done) {
      if (canSave) buttons.push({ action: 'save', label: `救 ${seatText(knifeSeat)}`, target: knifeSeat, disabled: false })
      if (poison) buttons.push(targetButton('poison', '毒', selection))
      buttons.push({ action: 'none', label: '不用药', target: null, disabled: false })
    }
    return {
      kind,
      knifeText: knifeSeat ? `${seatText(knifeSeat)}被刀` : '今晚平安',
      antidote: { left: antidote ? 1 : 0, usable: canSave },
      poison: { left: poison ? 1 : 0, usable: poison },
      done,
      buttons,
    }
  }

  if (kind === 'seer') {
    const result = info.checkedSeat ? `${seatText(info.checkedSeat)}是${info.checkResult || '好人'}` : ''
    return {
      kind,
      result,
      done,
      buttons: done ? [] : [targetButton('check', '查验', selection)],
    }
  }

  if (kind === 'guard') {
    const last = info.lastGuardSeat || null
    const repeat = Boolean(selection) && selection === last
    const guard = repeat
      ? { action: 'guard', label: `不能连守 ${seatText(selection)}`, target: selection, disabled: true }
      : targetButton('guard', '守', selection)
    return {
      kind,
      lastText: last ? `昨晚守了 ${seatText(last)}，今晚不能连守` : '',
      done,
      buttons: done ? [] : [guard, { action: 'none', label: '空守', target: null, disabled: false }],
    }
  }

  // 猎人（开枪状态由出局时后端决定，这里只确认）和系统还不会结算的角色（只播报）
  return {
    kind: kind === 'hunter' ? 'hunter' : 'generic',
    done,
    buttons: done ? [] : [{ action: 'confirm', label: kind === 'hunter' ? '确认' : '我知道了', target: null, disabled: false }],
  }
}

/**
 * 两侧座位上的角标。dead / disabled 所有人一样（出局的座位不能选）；
 * 狼队那一步狼人多看到 mate（队友）和 votes（几只狼选了这里）；selected 只是持有者自己点的。
 */
export function seatBadges(snapshot, selection = 0) {
  const badges = new Map()
  const info = snapshot?.nightInfo || {}
  const wolfStep = snapshot?.currentStep?.kind === 'wolf' && isStepHolder(snapshot)
  const mates = new Set(wolfStep ? info.teammateSeats || [] : [])
  const votes = new Map()
  if (wolfStep) {
    for (const pick of info.wolfPicks || []) {
      if (pick.decided && pick.targetSeatNo) votes.set(pick.targetSeatNo, (votes.get(pick.targetSeatNo) || 0) + 1)
    }
  }
  const holder = isStepHolder(snapshot)
  for (const player of snapshot?.players || []) {
    if (!player?.seatNo) continue
    const dead = player.alive === false
    badges.set(player.seatNo, {
      mate: mates.has(player.seatNo),
      votes: votes.get(player.seatNo) || 0,
      selected: holder && selection === player.seatNo,
      disabled: dead,
      dead,
    })
  }
  return badges
}
