// 出局待办（纯函数，有单测）：技能询问、开枪（猎人 / 狼王）、遗言、移交警徽。
// 快照里的 pending 只发给当事人和法官；其他人只看到「等待出局玩家确认」，不知道是谁、什么技能。
// 遗言例外：全场可见（快照 currentPending），本人「结束遗言」，法官可以「跳过」。

// 猎人和狼王共用开枪待办，文字里不写是谁
const KIND_LABELS = { skill_ask: '出局技能', hunter_shot: '开枪', last_words: '遗言', badge: '移交警徽' }

function waiting(detail = '') {
  return { kind: 'waiting', title: '等待出局玩家确认', detail, options: [], needsTarget: false, targets: [], buttons: [] }
}

/** selection 是当事人点选的目标座位（开枪、移交警徽用；0 = 没选）。 */
export function pendingPanel(snapshot, selection = 0) {
  const pending = snapshot?.pending
  const selfSeat = snapshot?.selfPlayer?.seatNo
  const words = snapshot?.currentPending
  if (words?.kind === 'last_words') return lastWordsPanel(snapshot, words, selfSeat)
  if (!pending) return waiting()
  if (snapshot.judgeViewer || !selfSeat || pending.seatNo !== selfSeat) {
    return waiting(snapshot.judgeViewer ? `${pending.seatNo} 号 · ${KIND_LABELS[pending.kind] || '待办'}` : '')
  }
  const options = pending.options || []
  const targets = (snapshot.players || [])
    .filter((player) => player?.seatNo && player.alive !== false && player.seatNo !== selfSeat)
    .map((player) => player.seatNo)
    .sort((a, b) => a - b)
  const pick = targets.includes(selection) ? selection : 0
  const targetButton = (label) => ({
    decision: 'use',
    label: pick ? label(pick) : '先点一个座位',
    target: pick || null,
    disabled: !pick,
  })

  if (pending.kind === 'hunter_shot') {
    return {
      kind: pending.kind, title: '开枪带走一人', options, needsTarget: true, targets, deadline: pending.deadline || null,
      buttons: [targetButton((seat) => `开枪带走 ${seat} 号`), { decision: 'skip', label: '不开枪', target: null, disabled: false }],
    }
  }
  if (pending.kind === 'badge') {
    return {
      kind: pending.kind, title: '移交警徽', options, needsTarget: true, targets, deadline: pending.deadline || null,
      buttons: [targetButton((seat) => `警徽给 ${seat} 号`), { decision: 'skip', label: '撕掉警徽', target: null, disabled: false }],
    }
  }
  const canUse = options.includes('use')
  return {
    kind: 'skill_ask',
    title: canUse ? '你出局了，要发动技能吗？' : '你出局了',
    options,
    needsTarget: false,
    targets: [],
    deadline: pending.deadline || null,
    buttons: canUse
      ? [{ decision: 'use', label: '发动技能', target: null, disabled: false }, { decision: 'skip', label: '不发动', target: null, disabled: false }]
      : [{ decision: 'skip', label: '确认', target: null, disabled: false }],
  }
}

/** 遗言：本人「结束遗言」，法官「跳过」（离席的人不用干等；无法官房主不能跳），其他人只看谁在说。 */
function lastWordsPanel(snapshot, words, selfSeat) {
  const own = Boolean(selfSeat) && words.seatNo === selfSeat
  const buttons = own
    ? [{ decision: 'skip', label: '结束遗言', target: null, disabled: false }]
    : snapshot?.judgeViewer ? [{ decision: 'skip', label: '跳过', target: null, disabled: false }] : []
  return {
    kind: 'last_words',
    title: own ? '请留遗言' : `${words.seatNo} 号留遗言`,
    hint: own ? '说完点「结束遗言」，到点自动结束。' : '到点自动结束。',
    options: words.options || ['skip'],
    needsTarget: false,
    targets: [],
    deadline: words.deadline || null,
    buttons,
  }
}
