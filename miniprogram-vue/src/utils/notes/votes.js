// 对局笔记的票型（纯函数，有单测）：按「投给谁」分组的视图，和面杀时两步记票的状态变化。
// 两步记票：先点被投的人，再点投他的人；换下一个被投的人时上一组自动记下。
// 一天里有两种票：放逐投票（警长的票算 1.5 票）和警长竞选（那时还没有警长，一人一票）。
import { aliveSeats, dayOf, seatOf } from './model.js'
import { toggleSheriff } from './board.js'

const seatsText = (list) => (list.length ? `${list.join('、')} 号` : '')
const weightText = (weight) => `${Number.isInteger(weight) ? weight : weight.toFixed(1)} 票`
const isSheriffVote = (entry) => entry.kind === 'sheriff'

function weightOf(note, voters, plain) {
  return voters.reduce((sum, no) => sum + (!plain && seatOf(note, no).sheriff ? 1.5 : 1), 0)
}

// plain：一人一票（警长竞选）。记下来时带了票数的用那个数（投票当时的），没带的按现在的警长算
function sortGroups(note, groups, plain = false) {
  return groups
    .map((group, index) => ({ ...group, weight: !plain && group.weight > 0 ? group.weight : weightOf(note, group.voters, plain), index }))
    .sort((a, b) => b.weight - a.weight || a.index - b.index)
}

// 票型页上的一个人：号码、我在座位上标的颜色（圆形头像的边框）、头像（法官房间里才有）
function personView(note, no, avatars) {
  return { no, tone: seatOf(note, no).guess || 'none', avatar: avatars?.[no] || '' }
}

// 一场投票在票型页上的样子，和房间里「记录」同一个画法：被投的人一行（头像 + 票条 + 票数），下面是投他的人。
// 票条按最高票的比例画；同一组里我标的狼够多时提示一句
function groupViews(note, votes, plain, avatars) {
  const groups = sortGroups(note, votes, plain)
  const max = groups.length ? groups[0].weight || 1 : 1
  return groups.map((group) => {
    const voters = group.voters.map((no) => personView(note, no, avatars))
    const wolves = voters.filter((voter) => voter.tone === 'wolf').length
    let hint = ''
    if (wolves >= 2 && wolves === voters.length) hint = `这 ${wolves} 张票都是你标成狼的人`
    else if (wolves >= 2) hint = `其中 ${wolves} 张是你标成狼的人`
    return {
      ...personView(note, group.target, avatars),
      target: group.target,
      title: `投 ${group.target} 号`,
      countText: weightText(group.weight) + (group.weight > group.voters.length ? ' · 含警长' : ''),
      pct: Math.round((group.weight / max) * 100),
      top: group.weight === max,
      voters,
      hint,
    }
  })
}

/** 开始记第 day 天的票（kind 为 'sheriff' 是警长竞选，否则是放逐投票）；那天已经记过就接着改。 */
export function startEntry(note, day, kind = 'exile') {
  const data = dayOf(note, day)
  const sheriff = kind === 'sheriff'
  const source = sheriff ? data.election.votes : data.votes
  return {
    day,
    kind: sheriff ? 'sheriff' : 'exile',
    target: 0,
    picked: [],
    groups: source.map((group) => ({ target: group.target, voters: [...group.voters] })),
    abstain: sheriff ? [] : data.abstain,
  }
}

// 当前这一组（被投的人 + 已点的投票人）并进 groups；没点人的组不留
function commit(entry) {
  const groups = entry.groups.filter((group) => group.target !== entry.target)
  if (entry.target && entry.picked.length) groups.push({ target: entry.target, voters: [...entry.picked].sort((a, b) => a - b) })
  return groups
}

/** 第一步：点被投的人。上一组自动记下；点一个已经记过的人，回到他那一组接着改。 */
export function pickTarget(entry, no) {
  const groups = commit(entry)
  const existing = groups.find((group) => group.target === no)
  return {
    ...entry,
    target: no,
    picked: existing ? existing.voters : [],
    groups: groups.filter((group) => group.target !== no),
    abstain: [],
  }
}

/** 第二步：点投给他的人。已经记在别的组里的人点了没反应。 */
export function toggleVoter(entry, no) {
  if (!entry.target || entry.groups.some((group) => group.voters.includes(no))) return entry
  const picked = entry.picked.includes(no) ? entry.picked.filter((item) => item !== no) : [...entry.picked, no]
  return { ...entry, picked, abstain: [] }
}

function unrecorded(note, entry) {
  const used = new Set([...entry.picked, ...entry.groups.flatMap((group) => group.voters)])
  return aliveSeats(note, entry.day).filter((no) => !used.has(no))
}

/** 其余的人都算弃票。 */
export function restAbstain(note, entry) {
  return { ...entry, abstain: unrecorded(note, entry) }
}

/**
 * 记完：写进笔记。
 * 放逐投票：最高票只有一个时记他被放逐（面杀不用再去点一次座位）。
 * 警长竞选：最高票只有一个时记他当选警长；平票不动原来的警长。
 */
export function finishEntry(note, entry) {
  const plain = isSheriffVote(entry)
  const groups = sortGroups(note, commit(entry), plain)
  const top = groups[0]
  const winner = top && (!groups[1] || groups[1].weight < top.weight) ? top.target : 0
  const votes = groups.map((group) => ({ target: group.target, voters: group.voters }))
  const data = dayOf(note, entry.day)
  if (plain) {
    const next = { ...note, dayData: { ...note.dayData, [entry.day]: { ...data, election: { ...data.election, votes, elected: winner, lost: false } } } }
    return winner && !seatOf(next, winner).sheriff ? toggleSheriff(next, winner) : next
  }
  let seats = note.seats
  if (winner && !seatOf(note, winner).out) {
    seats = { ...note.seats, [winner]: { ...seatOf(note, winner), out: 'exile', outDay: entry.day } }
  }
  const weighted = groups.map((group) => ({ target: group.target, voters: group.voters, weight: group.weight }))
  return { ...note, seats, dayData: { ...note.dayData, [entry.day]: { ...data, votes: weighted, abstain: entry.abstain, exiled: winner } } }
}

/** 记票那一屏：第一步的座位、第二步的座位、已经记下的几行、还没记的人。 */
export function entryView(note, entry) {
  const plain = isSheriffVote(entry)
  const alive = aliveSeats(note, entry.day)
  const votedFor = new Map()
  for (const group of entry.groups) for (const no of group.voters) votedFor.set(no, group.target)
  const current = entry.target && entry.picked.length ? [{ target: entry.target, voters: [...entry.picked].sort((a, b) => a - b) }] : []
  const lines = sortGroups(note, [...entry.groups, ...current], plain).map((group) => ({
    target: group.target,
    title: `投 ${group.target} 号`,
    votersText: seatsText(group.voters),
    countText: weightText(group.weight),
  }))
  const rest = unrecorded(note, entry)
  let remainingText = rest.length ? `还没记：${seatsText(rest)}` : ''
  if (entry.abstain.length) remainingText = `弃票：${seatsText(entry.abstain)}`
  return {
    title: plain ? `记第 ${entry.day} 天的警长投票` : `记第 ${entry.day} 天的票`,
    hint: plain ? '一人一票。最高票只有一个时，会把他记成警长。' : '警长的票算 1.5 票。最高票只有一个时，会把他记成被放逐。',
    targets: alive.map((no) => ({ no, on: entry.target === no, done: entry.groups.some((group) => group.target === no) })),
    step2: entry.target ? `再点投给 ${entry.target} 号的人` : '先在上面点被投的人',
    pickedText: entry.picked.length ? `已点 ${entry.picked.length} 人` : '',
    voters: alive.map((no) => ({
      no,
      on: entry.picked.includes(no),
      used: votedFor.has(no),
      label: votedFor.has(no) ? `投 ${votedFor.get(no)}` : (!plain && seatOf(note, no).sheriff ? '警长' : ''),
    })),
    lines,
    remainingText,
    // 警长竞选里警上的人本来就不投票，不记弃票
    canAbstain: !plain && rest.length > 0 && !entry.abstain.length,
  }
}

/**
 * 那一天的警长竞选：谁上警、谁退水、谁投了谁、谁当选。没有竞选（或还没记）返回 null。
 * 法官房间里自动记；面杀时用两步记票记下投票那一部分。avatars 是各座位的头像（房间里才有）。
 */
export function electionView(note, day, { avatars = {} } = {}) {
  const election = dayOf(note, day).election
  if (!election.up.length && !election.votes.length && !election.elected && !election.lost) return null
  let result = '还没出结果'
  if (election.elected) result = `${election.elected} 号当选`
  else if (election.lost) result = '警徽流失'
  else if (election.votes.length) result = '没有选出警长'
  const up = election.up.length ? `上警：${seatsText(election.up)}` : ''
  const off = election.off.length ? `退水：${seatsText(election.off)}` : ''
  return {
    title: `警长竞选 · ${result}`,
    upText: [up, off].filter(Boolean).join(' · '),
    up: election.up.map((no) => ({ ...personView(note, no, avatars), off: election.off.includes(no), elected: election.elected === no })),
    groups: groupViews(note, election.votes, true, avatars),
  }
}

// 面杀时这一天还能不能「记警长竞选的票」：第 1 天能；第 2 天只在第 1 天的竞选还没出结果（比如竞选推迟）时能
function canAddElection(note, day) {
  if (day > 2) return false
  const election = dayOf(note, day).election
  if (election.up.length || election.votes.length || election.elected || election.lost) return false
  if (day === 1) return true
  const first = dayOf(note, 1).election
  return !first.elected && !first.lost
}

/** 票型那一屏里的一天：警长竞选（有的话）和放逐投票是两块，各自按投给谁分组。 */
export function voteView(note, day, { avatars = {} } = {}) {
  const data = dayOf(note, day)
  const groups = groupViews(note, data.votes, false, avatars)
  const recorded = groups.length > 0 || data.abstain.length > 0
  let result = '还没记'
  if (recorded) result = data.exiled ? `${data.exiled} 号被放逐` : '没有人被放逐'
  return {
    day,
    recorded,
    title: `第 ${day} 天`,
    exileTitle: `放逐投票 · ${result}`,
    election: electionView(note, day, { avatars }),
    canAddElection: canAddElection(note, day),
    groups,
    abstain: data.abstain.map((no) => personView(note, no, avatars)),
    abstainText: data.abstain.length ? `弃票：${seatsText(data.abstain)}` : '',
    judgment: data.judgment,
  }
}
