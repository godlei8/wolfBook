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
  learn: (target) => `学习 ${seatText(target)}`,
  kill_pierce: (target) => `破盾刀 ${seatText(target)}`,
  none: () => '已确认',
  confirm: () => '已确认',
}

function doneText(info) {
  const make = info?.myAction ? DONE_TEXT[info.myAction] : null
  return make ? make(info.myTarget) : ''
}

// 机械狼一步里能选的几件事：slot 是提交的 actionType，verb 是按钮上的字
const MECH_SLOTS = {
  learn: '学习',
  check: '查验',
  poison: '毒',
  guard: '守',
  kill: '刀',
  kill_pierce: '破盾刀',
}

/** 机械狼今晚能做哪几件事：没学过可以学；学过且已生效按学到的技能；带刀时能刀人（学到狼人再多一刀破盾刀）。 */
function mechSlots(info) {
  const slots = []
  if (!info.learned) slots.push('learn')
  if (info.learned && info.learnedEffective) {
    if (info.learned === 'psychic') slots.push('check')
    if (info.learned === 'witch' && info.mechPoison !== false) slots.push('poison')
    if (info.learned === 'guard') slots.push('guard')
  }
  if (info.armed) {
    slots.push('kill')
    if (info.learned === 'wolf' && info.learnedEffective) slots.push('kill_pierce')
  }
  return slots
}

/**
 * 机械狼的面板：可以先点座位、再点「学习 / 刀 / …」把几件事放进草稿（draft：{ slot: 座位号 }），最后一次「确认提交」一起交。
 * 交过之后不再给按钮（后端每人每晚只认最后一次提交）。
 */
function mechWolfPanel(snapshot, selection, draft) {
  const info = snapshot.nightInfo || {}
  const day = snapshot.currentDay || 1
  const submitted = Array.isArray(info.myActions) ? info.myActions : []
  const armedText = day > 1 ? (info.armed ? '你现在带刀' : '你现在没带刀') : ''
  const learnedText = info.learned
    ? `已学到 ${info.learnedRole || ''}${info.learnedEffective ? '' : '（下一晚生效）'}`
    : '还没学习，本局可以学一次'
  const result = info.checkedSeat && info.identityResult ? `${seatText(info.checkedSeat)}是${info.identityResult}` : ''
  if (submitted.length) {
    const done = submitted
      .filter((item) => item.actionType && item.actionType !== 'none')
      .map((item) => `${MECH_SLOTS[item.actionType] || item.actionType} ${seatText(item.targetSeatNo)}`)
    return { kind: 'mech_wolf', armedText, learnedText, result, rows: [], done: done.length ? `已提交：${done.join('、')}` : '已确认', buttons: [] }
  }
  const slots = mechSlots(info)
  const chosen = slots.filter((slot) => draft && draft[slot])
  const rows = chosen.map((slot) => ({ slot, text: `${MECH_SLOTS[slot]} ${seatText(draft[slot])}` }))
  const buttons = slots.map((slot) => {
    if (slot === 'guard' && selection && selection === info.lastGuardSeat) {
      return { action: 'pick', slot, label: `不能连守 ${seatText(selection)}`, target: selection, disabled: true }
    }
    if (slot === 'learn' && selection && selection === snapshot.selfPlayer.seatNo) {
      return { action: 'pick', slot, label: '不能学习自己', target: selection, disabled: true }
    }
    return { ...targetButton(slot, MECH_SLOTS[slot], selection), action: 'pick', slot }
  })
  buttons.push({ action: 'none', label: '今晚不行动', target: null, disabled: false })
  buttons.push({
    action: 'submit',
    label: '确认提交',
    target: null,
    disabled: !chosen.length,
    actions: chosen.map((slot) => ({ actionType: slot, targetSeatNo: draft[slot] })),
  })
  return { kind: 'mech_wolf', armedText, learnedText, result, rows, done: '', buttons }
}

/**
 * 持有者的操作面板；不是持有者返回 null。selection 是持有者在两侧座位上点选的座位号（0 = 没选）。
 * 单人角色提交一次就定了（后端每人每晚只认最后一次提交，交完不再给按钮）；狼人可以改刀。
 * draft 只给机械狼：这一步已经放进草稿、还没提交的几件事。
 */
export function nightPanel(snapshot, selection = 0, draft = {}) {
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

  if (kind === 'mech_wolf') return mechWolfPanel(snapshot, selection, draft)

  if (kind === 'psychic') {
    const result = info.checkedSeat ? `${seatText(info.checkedSeat)}是${info.identityResult || ''}` : ''
    return { kind, result, done, buttons: done ? [] : [targetButton('check', '查验', selection)] }
  }

  if (kind === 'idle') {
    return { kind, done, buttons: done ? [] : [{ action: 'confirm', label: '我知道了', target: null, disabled: false }] }
  }

  if (kind === 'witch') {
    const knifeSeat = info.knifeSeat || null
    const antidote = info.antidote !== false
    const poison = info.poison !== false
    const canSave = antidote && Boolean(knifeSeat) && (knifeSeat !== self.seatNo || witchSelfSaveAllowed(snapshot))
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

/** 女巫今晚能不能救自己：跟板子（开局时拷进房间的 rules）走，默认不能自救；设了首夜可以自救的只有第一夜能。 */
export function witchSelfSaveAllowed(snapshot) {
  return snapshot?.rules?.witchSelfSave === 'first_night' && (snapshot?.currentDay || 1) === 1
}

/** 旧主持台女巫那一步的小标签。 */
export function witchSelfSaveText(snapshot) {
  if (snapshot?.rules?.witchSelfSave !== 'first_night') return '本板子不能自救'
  return witchSelfSaveAllowed(snapshot) ? '首夜可以自救' : '首夜已过，不能自救'
}
