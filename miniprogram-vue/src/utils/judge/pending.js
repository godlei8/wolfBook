// 出局待办（纯函数，有单测）：技能询问、猎人开枪、移交警徽。
// 快照里的 pending 只发给当事人和法官；其他人只看到「等待出局玩家确认」，不知道是谁、什么技能。

const KIND_LABELS = { skill_ask: '出局技能', hunter_shot: '猎人开枪', badge: '移交警徽' }

function waiting(detail = '') {
  return { kind: 'waiting', title: '等待出局玩家确认', detail, options: [], needsTarget: false, targets: [], buttons: [] }
}

/** selection 是当事人点选的目标座位（开枪、移交警徽用；0 = 没选）。 */
export function pendingPanel(snapshot, selection = 0) {
  const pending = snapshot?.pending
  const selfSeat = snapshot?.selfPlayer?.seatNo
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
