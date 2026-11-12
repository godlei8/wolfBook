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

/** 狼队刀口，规则同后端：选了的狼都指同一个人才算，没统一是空刀；没选的狼不算（控刀）。 */
function knifeOf(picks) {
  const chosen = picks.filter((pick) => pick.decided)
  if (!chosen.length) return { seat: null, text: '还没选' }
  const targets = new Set(chosen.map((pick) => pick.targetSeatNo || null))
  if (targets.size > 1) return { seat: null, text: '没统一 · 空刀' }
  const seat = chosen[0].targetSeatNo || null
  if (!seat) return { seat: null, text: '空刀' }
  return { seat, text: `${seatText(seat)} · ${chosen.length} 票` }
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
  kill_pierce: (target) => `已出破盾刀 ${seatText(target)}`,
  none: () => '已确认',
  confirm: () => '已确认',
}

function doneText(info) {
  const make = info?.myAction ? DONE_TEXT[info.myAction] : null
  return make ? make(info.myTarget) : ''
}

// 机械狼那一步能做的事：actionType → 按钮上的字。普通刀口不在这一步（带刀时到狼人那一步选）
const MECH_VERBS = {
  learn: '学习',
  check: '查验',
  poison: '毒',
  guard: '守',
  kill_pierce: '破盾刀',
}

/** 机械狼那一步能做什么：没学过可以学；学过且已生效按学到的技能，学到狼人且带刀时是破盾刀。一次只做一件事。 */
function mechActions(info) {
  if (!info.learned) return ['learn']
  if (!info.learnedEffective) return []
  if (info.learned === 'psychic') return ['check']
  if (info.learned === 'witch') return info.mechPoison !== false ? ['poison'] : []
  if (info.learned === 'guard') return ['guard']
  if (info.learned === 'wolf' && info.armed) return ['kill_pierce']
  return []
}

/**
 * 机械狼那一步（用户给定规则）：第二晚起先告诉它开刀状态；技能都在这一步用，交一次就定了。
 * 带刀时普通刀口要到狼人那一步再选，这里只提示。
 */
function mechWolfPanel(snapshot, selection) {
  const info = snapshot.nightInfo || {}
  const day = snapshot.currentDay || 1
  const armedText = day > 1 ? (info.armed ? '能开刀：刀口到狼人那一步再选' : '还不能开刀（狼人还在）') : ''
  const learnedText = info.learned
    ? `已学到 ${info.learnedRole || ''}${info.learnedEffective ? '' : '（下一晚生效）'}`
    : '还没学习，本局可以学一次'
  const result = info.checkedSeat && info.identityResult ? `${seatText(info.checkedSeat)}是${info.identityResult}` : ''
  const done = doneText(info)
  if (done) return { kind: 'mech_wolf', armedText, learnedText, result, done, buttons: [] }
  const buttons = mechActions(info).map((action) => {
    if (action === 'guard' && selection && selection === info.lastGuardSeat) {
      return { action, label: `不能连守 ${seatText(selection)}`, target: selection, disabled: true }
    }
    if (action === 'learn' && selection && selection === snapshot.selfPlayer.seatNo) {
      return { action, label: '不能学习自己', target: selection, disabled: true }
    }
    return targetButton(action, MECH_VERBS[action], selection)
  })
  buttons.push({ action: 'none', label: '今晚不行动', target: null, disabled: false })
  return { kind: 'mech_wolf', armedText, learnedText, result, done: '', buttons }
}

/**
 * 狼人那一步（用户给定规则）：选了刀口或空刀之后点「确定」；所有存活的狼人都确定了 5 秒后结束，
 * 确定之后可以「重新选择」。狼队全出局后带刀的机械狼一个人在这一步选刀口，规则一样。
 */
function wolfPanel(snapshot, selection) {
  const info = snapshot.nightInfo || {}
  const self = snapshot.selfPlayer
  const picks = info.wolfPicks && info.wolfPicks.length
    ? info.wolfPicks
    : [{
        seatNo: self.seatNo,
        targetSeatNo: info.myAction === 'kill' ? info.myTarget : null,
        decided: info.myAction === 'kill' || info.myAction === 'none',
        confirmed: Boolean(info.myConfirmed),
      }]
  const mine = picks.find((pick) => pick.seatNo === self.seatNo)
  const picked = Boolean(mine && mine.decided)
  const confirmed = Boolean(info.myConfirmed || (mine && mine.confirmed))
  let buttons
  if (confirmed) {
    buttons = [{ action: 'reselect', label: '重新选择', target: null, disabled: false }]
  } else {
    buttons = [{ action: 'none', label: '空刀', target: null, disabled: false }, targetButton('kill', '刀', selection)]
    if (picked) buttons.push({ action: 'confirm', label: '确定', target: null, disabled: false, tone: 'gold' })
  }
  const allConfirmed = picks.length > 0 && picks.every((pick) => pick.confirmed)
  return {
    kind: 'wolf',
    rows: picks.map((pick) => ({
      seatNo: pick.seatNo,
      me: pick.seatNo === self.seatNo,
      text: (!pick.decided ? '还没选' : pick.targetSeatNo ? seatText(pick.targetSeatNo) : '空刀') + (pick.confirmed ? ' ✓' : ''),
    })),
    knife: knifeOf(picks),
    done: confirmed ? (allConfirmed ? '都确定了，5 秒后结束' : '已确定，等队友确定') : '',
    buttons,
  }
}

/**
 * 持有者的操作面板；不是持有者返回 null。selection 是持有者在两侧座位上点选的座位号（0 = 没选）。
 * 单人角色提交一次就定了（后端每人每步只认最后一次提交，交完不再给按钮）；狼人可以改刀，选好后点确定。
 */
export function nightPanel(snapshot, selection = 0) {
  if (!isStepHolder(snapshot)) return null
  const kind = snapshot.currentStep.kind
  const info = snapshot.nightInfo || {}
  const self = snapshot.selfPlayer
  const done = doneText(info)

  if (kind === 'wolf') return wolfPanel(snapshot, selection)

  if (kind === 'mech_wolf') return mechWolfPanel(snapshot, selection)

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
      // 没解药时后端不下发刀口（roles/女巫.md），不能显示成平安
      knifeText: !antidote ? '解药已用完，不再告知刀口' : knifeSeat ? `${seatText(knifeSeat)}被刀` : '今晚平安',
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

/** 女巫今晚能不能救自己：roles/女巫.md 全程不可自救，旧房间 rules 里存的 first_night 也不算。 */
export function witchSelfSaveAllowed(_snapshot) {
  return false
}

/** 旧主持台女巫那一步的小标签。 */
export function witchSelfSaveText(_snapshot) {
  return '全程不能自救'
}
