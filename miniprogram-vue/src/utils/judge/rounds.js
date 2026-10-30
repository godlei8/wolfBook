// 对局记录和胜负（纯函数，有单测）。对局中只有公开信息（rounds）；结束后把 nightHistory 按天合进去。

const seats = (list) => (list || []).join('、')

const NIGHT_VERBS = { kill: '刀', save: '救', poison: '毒', check: '查验', guard: '守', 刀人: '刀', 解药: '救', 毒药: '毒', 查验: '查验', 守护: '守' }

function sheriffText(sheriff) {
  if (!sheriff) return ''
  const detail = [`上警 ${seats(sheriff.candidates) || '无'}`]
  if (sheriff.withdrew?.length) detail.push(`退水 ${seats(sheriff.withdrew)}`)
  if (sheriff.electedSeatNo) return `${sheriff.electedSeatNo} 号当选警长（${detail.join('，')}）`
  return `${sheriff.lost ? '警徽流失' : '没有警长'}（${detail.join('，')}）`
}

function speechOrderText(order) {
  if (!order?.order?.length) return ''
  return `${order.startSeat ? `从 ${order.startSeat} 号开始` : '发言顺序'} · ${order.order.join('、')}`
}

function exileRow(exile) {
  return {
    tallies: (exile.tallies || []).map((tally) => `${tally.targetSeatNo} 号 ${tally.weight} 票 ← ${seats(tally.voterSeatNos)}`),
    abstain: exile.abstainSeatNos?.length ? `弃票 ${seats(exile.abstainSeatNos)}` : '',
    out: exile.eliminatedSeatNo ? `${exile.eliminatedSeatNo} 号出局` : '',
    pk: exile.pkSeatNos?.length ? `${seats(exile.pkSeatNos)} 号平票进入 PK` : '',
  }
}

function skillText(skill) {
  switch (skill.kind) {
    case 'hunter_shot':
      return `${skill.actorSeatNo} 号开枪带走 ${skill.targetSeatNo} 号`
    case 'badge_passed':
      return skill.targetSeatNo ? `${skill.actorSeatNo} 号把警徽交给 ${skill.targetSeatNo} 号` : `${skill.actorSeatNo} 号撕掉警徽`
    case 'idiot_revealed':
      return `${skill.actorSeatNo} 号翻牌是白痴`
    case 'force_out':
      return `法官点杀 ${skill.targetSeatNo || skill.actorSeatNo} 号`
    default:
      return ''
  }
}

function nightActionText(action, roleBySeat) {
  const role = roleBySeat.get(action.actorSeatNo) || ''
  const verb = NIGHT_VERBS[action.actionType]
  const what = verb && action.targetSeatNo ? `${verb} ${action.targetSeatNo} 号` : action.actionType === 'confirm' ? '确认' : '不行动'
  return `${action.actorSeatNo} 号${role} ${what}`
}

export function roundRows(snapshot) {
  const finished = snapshot?.roomStatus === 'finished'
  const roleBySeat = new Map((snapshot?.players || []).filter((player) => player?.seatNo).map((player) => [player.seatNo, player.roleName || '']))
  return (snapshot?.rounds || []).map((round) => ({
    dayNo: round.dayNo,
    nightDeaths: round.nightDeaths?.length ? `${seats(round.nightDeaths)} 号` : '平安夜',
    sheriff: sheriffText(round.sheriff),
    speechOrder: speechOrderText(round.speechOrder),
    exiles: (round.exile || []).map(exileRow),
    skills: (round.skills || []).map(skillText).filter(Boolean),
    nightActions: finished
      ? (snapshot.nightHistory || []).filter((action) => action.dayNo === round.dayNo).map((action) => nightActionText(action, roleBySeat))
      : [],
  }))
}

export function verdictText(snapshot) {
  const camp = snapshot?.winVerdict?.camp || snapshot?.winnerCamp || ''
  if (!camp || camp === '流局') return { title: '流局', reason: snapshot?.winVerdict?.reason || '对局被提前结束，不计胜负' }
  return { title: `${camp}阵营胜利`, reason: snapshot?.winVerdict?.reason || '' }
}
