// 对局记录（纯函数，有单测）：「按天看」每天一张夜卡 + 一张白天卡，「按人看」每个人一行经历。
// 对局中只有公开信息（rounds）；结束后（或法官）多出夜间行动（nightHistory）和死因（rounds[].deathCauses）。
// 人都用「头像 + 座位号角标」显示；不记发言顺序（用户给定）。
import { campOf } from './judge-bar.js'
import { verdictText } from './rounds.js'

// 没头像时按座位号给底色（和原型同一组）
const AVATAR_COLORS = ['#c9b6f2', '#9fd8e0', '#f2c38f', '#a8d5a2', '#f4a6b8', '#b9c4f7', '#e7d48a', '#f7b39a', '#c2b59b', '#9ee6c5', '#d7a6f0', '#f2b33d']

// 旧版小程序交的中文名
const LEGACY = { 刀人: 'kill', 解药: 'save', 毒药: 'poison', 查验: 'check', 守护: 'guard' }

const VERBS = { kill: '刀', kill_pierce: '破盾刀', learn: '学习', save: '用解药救', poison: '毒', check: '验', guard: '守' }
// 按人看里的短说法
const STORY_VERBS = { kill: '刀', kill_pierce: '破盾刀', learn: '学', save: '救', poison: '毒', check: '验', guard: '守' }
const PASS = { wolf: '空刀', wolf_king: '空刀', witch: '不用药', guard: '空守' }

const CAUSES = { knife: '被刀', guard_save: '同守同救', pierce: '破盾刀', poison: '被毒', bounce: '毒被弹回', mech_poison: '被毒' }
const POISONED = new Set(['poison', 'bounce', 'mech_poison'])
const SHOOTERS = new Set(['hunter', 'wolf_king'])

const join = (seats) => seats.join('、')
const votes = (weight) => `${Number(weight) || 0} 票`

function personOf(bySeat) {
  return (seatNo) => {
    const player = bySeat.get(seatNo)
    const nickname = player?.nickname || ''
    return {
      seatNo,
      nickname,
      initial: nickname.slice(0, 1) || String(seatNo),
      avatar: player?.avatar || '',
      color: AVATAR_COLORS[(Math.max(1, seatNo) - 1) % AVATAR_COLORS.length],
    }
  }
}

function actionOf(item) {
  const type = item?.actionType || ''
  return LEGACY[type] || type
}

function campKey(player) {
  return player?.roleName ? campOf(player) : null
}

function checkNote(actor, target) {
  if (!target?.roleName) return ''
  const seer = actor?.mechanic === 'seer' || actor?.roleName === '预言家'
  if (seer) return target.faction === '狼人' ? '狼人' : '好人'
  return target.roleName
}

function nightActions(items, bySeat, person) {
  const rows = []
  for (const item of items) {
    const action = actionOf(item)
    if (action === 'confirm' || action === 'reselect') continue
    const actor = bySeat.get(item.actorSeatNo)
    const role = actor?.roleName || ''
    const target = item.targetSeatNo ? bySeat.get(item.targetSeatNo) : null
    if (action === 'kill') {
      // 狼队刀同一个人合成一行
      const same = rows.find((row) => row.action === 'kill' && row.target?.seatNo === item.targetSeatNo)
      if (same) {
        same.actors.push(person(item.actorSeatNo))
        same.note = `${same.actors.length} 票`
        continue
      }
    }
    const verb = action === 'none' ? PASS[actor?.mechanic] || '不行动' : VERBS[action] || action
    let note = ''
    if (action === 'kill') note = '1 票'
    else if (action === 'check') note = checkNote(actor, target)
    else if (action === 'learn' && target?.roleName) note = `学到${target.roleName}`
    rows.push({
      action,
      actors: [person(item.actorSeatNo)],
      // 狼队那一行写「狼人」（机械狼带刀时也在这一行）
      role: action === 'kill' && actor?.faction === '狼人' ? '狼人' : role,
      camp: campKey(actor),
      verb,
      target: item.targetSeatNo ? person(item.targetSeatNo) : null,
      note,
    })
  }
  return rows.map(({ action, ...row }) => row)
}

function causeText(codes, player) {
  const list = (codes || []).map((code) => CAUSES[code]).filter(Boolean)
  if (list.length && (codes || []).some((code) => POISONED.has(code)) && SHOOTERS.has(player?.mechanic)) list.push('不能开枪')
  return [...new Set(list)].join(' · ')
}

function nightCard(round, history, bySeat, person) {
  const deaths = (round.nightDeaths || []).map((seatNo) => ({
    person: person(seatNo),
    role: bySeat.get(seatNo)?.roleName || '',
    cause: causeText(round.deathCauses?.[seatNo], bySeat.get(seatNo)),
  }))
  return {
    title: `第 ${round.dayNo} 夜`,
    result: deaths.length ? `${deaths.length} 人出局` : '平安夜',
    deaths,
    actions: nightActions(history, bySeat, person),
  }
}

function bars(tallies, person) {
  const sorted = [...(tallies || [])].sort((a, b) => b.weight - a.weight)
  const max = sorted.length ? Number(sorted[0].weight) || 1 : 1
  return sorted.map((tally) => ({
    person: person(tally.targetSeatNo),
    count: votes(tally.weight),
    pct: Math.round(((Number(tally.weight) || 0) / max) * 100),
    top: Number(tally.weight) === max,
    voters: (tally.voterSeatNos || []).map(person),
  }))
}

function sheriffBlock(sheriff, person) {
  if (!sheriff) return null
  const withdrew = new Set(sheriff.withdrew || [])
  const elected = sheriff.electedSeatNo
  const tally = (sheriff.tallies || []).find((item) => item.targetSeatNo === elected)
  return {
    candidates: (sheriff.candidates || []).map((seatNo) => ({ person: person(seatNo), withdrew: withdrew.has(seatNo), elected: seatNo === elected })),
    result: elected ? `${elected} 号当选${tally ? ` · ${votes(tally.weight)}` : ''}` : sheriff.lost ? '警徽流失' : '没有警长',
    bars: bars(sheriff.tallies, person),
  }
}

function eventOf(skill, person) {
  const a = skill.actorSeatNo ? person(skill.actorSeatNo) : null
  const b = skill.targetSeatNo ? person(skill.targetSeatNo) : null
  switch (skill.kind) {
    case 'hunter_shot':
      // 猎人、狼王同一句，不暴露是谁开的枪
      return { tag: '技能', kind: 'skill', a, text: '发动技能，带走了', b }
    case 'badge_passed':
      return b ? { tag: '警徽', kind: 'badge', a, text: '把警徽交给', b } : { tag: '警徽', kind: 'badge', a, text: '撕掉了警徽', b: null }
    case 'idiot_revealed':
      return { tag: '翻牌', kind: 'skill', a, text: '翻牌是白痴', b: null }
    case 'self_destruct':
      return { tag: '自爆', kind: 'wolf', a, text: '自爆', b: null }
    case 'force_out':
      return { tag: '点杀', kind: 'wolf', a: b || a, text: '被法官点杀', b: null }
    default:
      return null
  }
}

function dayCard(round, person) {
  const exile = round.exile || []
  const skills = round.skills || []
  if (!round.sheriff && !exile.length && !skills.length) return null
  const out = exile.map((item) => item.eliminatedSeatNo).find(Boolean)
  const exploded = skills.find((skill) => skill.kind === 'self_destruct')
  return {
    title: `第 ${round.dayNo} 天`,
    result: out ? `${out} 号被放逐` : exploded ? `${exploded.actorSeatNo} 号自爆` : exile.length ? '无人出局' : '',
    sheriff: sheriffBlock(round.sheriff, person),
    votes: exile.map((item, index) => ({
      label: index ? 'PK 投票' : '放逐投票',
      result: item.eliminatedSeatNo
        ? `${item.eliminatedSeatNo} 号出局`
        : item.pkSeatNos?.length ? `${join(item.pkSeatNos)} 号平票，进入 PK` : '没有人出局',
      bars: bars(item.tallies, person),
      abstain: (item.abstainSeatNos || []).map(person),
    })),
    events: skills.map((skill) => eventOf(skill, person)).filter(Boolean),
  }
}

function uniq(list) {
  return [...new Set(list)]
}

/** 每个人的经历：上警 → 夜里做了什么（结束后）→ 投了谁 → 技能 → 怎么出局。 */
function peopleOf(snapshot, rounds, history, bySeat, person) {
  const seated = (snapshot?.players || []).filter((player) => player?.seatNo).sort((a, b) => a.seatNo - b.seatNo)
  return seated.map((player) => {
    const seat = player.seatNo
    const parts = []
    let fate = ''
    let fateText = ''
    let badgeTaken = false
    for (const round of rounds) {
      const sheriff = round.sheriff
      if (sheriff?.electedSeatNo === seat) parts.push('当选警长')
      else if ((sheriff?.candidates || []).includes(seat)) parts.push((sheriff.withdrew || []).includes(seat) ? '上警后退水' : '上警')
      if (!badgeTaken && (round.skills || []).some((skill) => skill.kind === 'badge_passed' && skill.targetSeatNo === seat)) {
        badgeTaken = true
        parts.push('接过警徽')
      }
    }
    // 夜里：同一种行动合成一句「刀 5、2 号」
    const byVerb = new Map()
    for (const item of history.filter((entry) => entry.actorSeatNo === seat)) {
      const action = actionOf(item)
      if (!STORY_VERBS[action] || !item.targetSeatNo) continue
      if (!byVerb.has(action)) byVerb.set(action, [])
      byVerb.get(action).push(item.targetSeatNo)
    }
    for (const [action, targets] of byVerb) parts.push(`${STORY_VERBS[action]} ${join(uniq(targets))} 号`)
    const voted = []
    for (const round of rounds) {
      for (const item of round.exile || []) {
        for (const tally of item.tallies || []) if ((tally.voterSeatNos || []).includes(seat)) voted.push(tally.targetSeatNo)
      }
    }
    if (voted.length) parts.push(`投 ${join(uniq(voted))} 号`)
    for (const round of rounds) {
      for (const skill of round.skills || []) {
        if (skill.actorSeatNo !== seat) continue
        if (skill.kind === 'hunter_shot' && skill.targetSeatNo) parts.push(`带走 ${skill.targetSeatNo} 号`)
        else if (skill.kind === 'badge_passed' && !skill.targetSeatNo) parts.push('撕掉警徽')
        else if (skill.kind === 'badge_passed') parts.push(`把警徽交给 ${skill.targetSeatNo} 号`)
        else if (skill.kind === 'idiot_revealed') parts.push('翻牌')
      }
    }
    for (const round of rounds) {
      if (fate) break
      if ((round.nightDeaths || []).includes(seat)) {
        fate = `第 ${round.dayNo} 夜`
        fateText = `第 ${round.dayNo} 夜${causeText(round.deathCauses?.[seat], player).split(' · ')[0] || '出局'}`
        continue
      }
      for (const skill of round.skills || []) {
        if (fate) break
        if (skill.kind === 'hunter_shot' && skill.targetSeatNo === seat) {
          fate = `第 ${round.dayNo} 天`
          fateText = `被 ${skill.actorSeatNo} 号带走`
        } else if (skill.kind === 'self_destruct' && skill.actorSeatNo === seat) {
          fate = `第 ${round.dayNo} 天`
          fateText = `第 ${round.dayNo} 天自爆`
        } else if (skill.kind === 'force_out' && (skill.targetSeatNo || skill.actorSeatNo) === seat) {
          fate = `第 ${round.dayNo} 天`
          fateText = '被法官点杀'
        }
      }
      if (!fate && (round.exile || []).some((item) => item.eliminatedSeatNo === seat)) {
        fate = `第 ${round.dayNo} 天`
        fateText = `第 ${round.dayNo} 天被放逐`
      }
    }
    if (fateText) parts.push(fateText)
    const dead = player.alive === false
    return {
      person: person(seat),
      role: player.roleName || '',
      camp: campKey(player),
      story: parts.join(' · ') || '还没有记录',
      fate: dead ? fate || '出局' : '存活',
      dead,
      me: Boolean(player.self),
    }
  })
}

const VERDICT_CAMP = { 好人: 'good', 狼人: 'wolf' }

export function gameLog(snapshot) {
  const bySeat = new Map((snapshot?.players || []).filter((player) => player?.seatNo).map((player) => [player.seatNo, player]))
  const person = personOf(bySeat)
  const rounds = snapshot?.rounds || []
  const history = snapshot?.nightHistory || []
  const finished = snapshot?.roomStatus === 'finished'
  let verdict = null
  if (finished) {
    const text = verdictText(snapshot)
    const camp = snapshot?.winVerdict?.camp || snapshot?.winnerCamp || ''
    verdict = { ...text, camp: VERDICT_CAMP[camp] || 'draw', days: `共 ${rounds.length} 天` }
  }
  return {
    verdict,
    days: rounds.map((round) => ({
      dayNo: round.dayNo,
      night: nightCard(round, history.filter((item) => item.dayNo === round.dayNo), bySeat, person),
      day: dayCard(round, person),
    })),
    people: peopleOf(snapshot, rounds, history, bySeat, person),
  }
}
