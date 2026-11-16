// 对局笔记的座位盘（纯函数，有单测）：每个号码块显示什么、每天顶上的一行（昨夜谁出局、警长）、狼坑汇总、点座位的面板，
// 以及每次点击之后的新笔记。没有单独的夜晚页：夜里出局的记在天亮的那一天。
// 所有「改」都返回一份新笔记，不动传进来的那份。
import { dayOf, sayOf, seatOf } from './model.js'

export const GUESS_OPTIONS = [
  { value: 'wolf', label: '狼人', short: '狼' },
  { value: 'good', label: '好人', short: '好' },
  { value: 'god', label: '神职', short: '神' },
]
export const DEFAULT_CLAIMS = ['预言家', '女巫', '猎人', '守卫', '白痴', '平民']
// 要再点一个座位才记得下来的标记：谁站谁、谁踩谁、谁给谁金水、谁查杀谁
export const TARGET_TAGS = { stand: '站', step: '踩', gold: '金水', kill: '查杀' }
const PENDING_VERB = { stand: '站谁', step: '踩谁', gold: '给谁金水', kill: '查杀谁' }
const IDLE_TAG = '划水'
// 号码块上的小格：带目标的标记缩成一个字加座位号，各有各的颜色
const MARK_SHORT = [['站 ', '站', 'stand'], ['踩 ', '踩', 'step'], ['金水 ', '金', 'gold'], ['查杀 ', '杀', 'kill']]
const MAX_MARKS = 2

const seatsText = (list) => list.map((no) => `${no} 号`).join('、')

function withSeat(note, no, patch) {
  return { ...note, seats: { ...note.seats, [no]: { ...seatOf(note, no), ...patch } } }
}

function withSay(note, day, no, patch) {
  const dayMap = { ...(note.says?.[day] || {}), [no]: { ...sayOf(note, day, no), ...patch } }
  return { ...note, says: { ...note.says, [day]: dayMap } }
}

function withDay(note, day, patch) {
  return { ...note, dayData: { ...note.dayData, [day]: { ...dayOf(note, day), ...patch } } }
}

// 第 day 夜出局的座位（天亮时公布的那几个）
function nightDead(note, day) {
  const seats = []
  for (let no = 1; no <= note.playerCount; no += 1) {
    const seat = seatOf(note, no)
    if (seat.out === 'night' && seat.outDay === day) seats.push(no)
  }
  return seats
}

/** 我猜他是：再点一次同一个、或传空，就是取消。 */
export function setGuess(note, no, guess) {
  return withSeat(note, no, { guess: seatOf(note, no).guess === guess ? '' : guess })
}

/** 他说自己是：再点一次取消。 */
export function toggleClaim(note, no, claim) {
  return withSeat(note, no, { claim: seatOf(note, no).claim === claim ? '' : claim })
}

/** 这一天他的一个标记（比如「划水」）：有就去掉，没有就加上。 */
export function toggleTag(note, day, no, tag) {
  const tags = sayOf(note, day, no).tags
  return withSay(note, day, no, { tags: tags.includes(tag) ? tags.filter((item) => item !== tag) : [...tags, tag] })
}

/** 带目标的标记：no 号站 / 踩 / 金水 / 查杀 target 号。可以指自己（对跳时说站自己）。 */
export function addTargetTag(note, day, no, kind, target) {
  if (!TARGET_TAGS[kind] || !target) return note
  return toggleTag(note, day, no, `${TARGET_TAGS[kind]} ${target}`)
}

export function setSayText(note, day, no, text) {
  return withSay(note, day, no, { text: String(text || '').trim().slice(0, 60) })
}

/** 出局：同一种方式再点一次取消；换一种方式就改过去。 */
export function toggleOut(note, no, kind, day) {
  const seat = seatOf(note, no)
  if (seat.out === kind && seat.outDay === day) return withSeat(note, no, { out: '', outDay: 0 })
  const next = withSeat(note, no, { out: kind, outDay: day })
  // 标了有人夜里出局，这一夜就不是平安夜了
  return kind === 'night' && dayOf(note, day).peace ? withDay(next, day, { peace: false }) : next
}

/** 第 day 天之前那一夜是平安夜：再点一次取消。记成平安夜时，那一夜标过的出局去掉。 */
export function togglePeace(note, day) {
  if (dayOf(note, day).peace) return withDay(note, day, { peace: false })
  let next = note
  for (const no of nightDead(note, day)) next = withSeat(next, no, { out: '', outDay: 0 })
  return withDay(next, day, { peace: true })
}

/** 警长只有一个：点谁谁当，再点一次取消。 */
export function toggleSheriff(note, no) {
  const on = !seatOf(note, no).sheriff
  const seats = {}
  for (let seat = 1; seat <= note.playerCount; seat += 1) {
    const current = seatOf(note, seat)
    if (current.sheriff || note.seats?.[seat]) seats[seat] = { ...current, sheriff: false }
  }
  seats[no] = { ...seatOf(note, no), sheriff: on }
  return { ...note, seats }
}

export function setJudgment(note, day, text) {
  return withDay(note, day, { judgment: String(text || '').trim().slice(0, 200) })
}

export function outLabel(seat) {
  if (!seat.out) return ''
  if (seat.out === 'night') return seat.outDay <= 1 ? '首夜出局' : `第 ${seat.outDay} 夜出局`
  if (seat.out === 'exile') return `第 ${seat.outDay} 天放逐`
  if (seat.out === 'shot') return `第 ${seat.outDay} 天被带走`
  if (seat.out === 'force') return `第 ${seat.outDay} 天被点杀`
  return `第 ${seat.outDay} 天自爆`
}

function sayLine(say) {
  return [...say.tags, say.text].filter(Boolean).join(' · ')
}

function markOf(tag) {
  for (const [prefix, short, tone] of MARK_SHORT) {
    if (tag.startsWith(prefix)) return { text: short + tag.slice(prefix.length), tone }
  }
  return { text: tag, tone: tag === IDLE_TAG ? 'idle' : 'plain' }
}

// 号码块上最多两格；放不下时留第一格，其余收成「+N」。写了一句话的多一格「注」
function seatMarks(say) {
  const all = say.tags.map(markOf)
  if (say.text) all.push({ text: '注', tone: 'note' })
  if (all.length <= MAX_MARKS) return all
  return [all[0], { text: `+${all.length - 1}`, tone: 'more' }]
}

/**
 * 座位盘：一个座位一个号码块。tone 决定整块的颜色（wolf / good / god / none）；marks 是这一天的标记小格；
 * out 是怎么出局的（还在场为空）；blank 是什么都没标的安静块（只有号码，房间里多头像和昵称）。
 * ui 是画面上的状态：选中谁、谁在发言、我是几号、昵称和头像。
 */
export function seatCards(note, day, ui = {}) {
  const claimCount = {}
  for (let no = 1; no <= note.playerCount; no += 1) {
    const claim = seatOf(note, no).claim
    if (claim) claimCount[claim] = (claimCount[claim] || 0) + 1
  }
  const cards = []
  for (let no = 1; no <= note.playerCount; no += 1) {
    const seat = seatOf(note, no)
    const gone = Boolean(seat.out) && seat.outDay <= day
    const say = sayOf(note, day, no)
    const guess = GUESS_OPTIONS.find((item) => item.value === seat.guess)
    const standoff = Boolean(seat.claim) && claimCount[seat.claim] > 1
    const out = gone ? outLabel(seat) : ''
    const marks = gone ? [] : seatMarks(say)
    cards.push({
      no,
      name: ui.names?.[no] || seat.name || '',
      avatar: ui.avatars?.[no] || '',
      tone: seat.guess || 'none',
      guessShort: guess ? guess.short : '',
      claim: seat.claim ? `${standoff ? '对跳' : '跳'}${seat.claim}` : '',
      standoff,
      sheriff: seat.sheriff,
      gone,
      out,
      marks,
      line: gone ? out : sayLine(say),
      blank: !seat.guess && !seat.claim && !gone && !marks.length,
      selected: ui.selected === no,
      speaking: ui.speaking === no,
      me: ui.me === no,
    })
  }
  return cards
}

/**
 * 每天顶上的一行：昨夜谁出局（夜里出局的记在天亮的那一天）、警长是谁。
 * 面杀时昨夜还没记会提醒（nightMissing），没人夜里出局时旁边有「平安夜」可以点（canPeace）；房间里是自动记的，没记到就不说。
 */
export function daySummary(note, day, { room = false } = {}) {
  const dead = nightDead(note, day)
  const peace = dayOf(note, day).peace && !dead.length
  const nightMissing = !room && !dead.length && !peace
  const parts = []
  if (dead.length) parts.push(`昨夜 ${seatsText(dead)}出局`)
  else if (peace) parts.push('昨夜平安夜')
  else if (nightMissing) parts.push('昨夜还没记')
  if (day >= (note.days || 1)) {
    for (let no = 1; no <= note.playerCount; no += 1) {
      if (seatOf(note, no).sheriff) parts.push(`警长 ${no} 号`)
    }
  } else if (dayOf(note, day).election.elected) {
    // 往回翻到前几天：不拿现在的警长去说当时，只说那天谁当选
    parts.push(`${dayOf(note, day).election.elected} 号当选警长`)
  }
  // 已经标了有人夜里出局，就不再给「平安夜」，免得一点把刚记的冲掉
  return { text: parts.join(' · '), nightMissing, peace, canPeace: !room && !dead.length }
}

/** 我的狼坑：座位上标成狼的那几个，自动汇总；知道板子有几狼时说还差几个。 */
export function wolfPit(note) {
  const seats = []
  for (let no = 1; no <= note.playerCount; no += 1) {
    const seat = seatOf(note, no)
    if (seat.guess === 'wolf') seats.push({ no, gone: Boolean(seat.out) })
  }
  const count = seats.length
  const wolves = note.wolves || 0
  let text = '点座位，标出你怀疑的人'
  if (count && !wolves) text = `标了 ${count} 个`
  else if (count && count < wolves) text = `${wolves} 狼还差 ${wolves - count} 个`
  else if (count && count === wolves) text = `${wolves} 狼标齐了`
  else if (count) text = `标了 ${count} 个，板子只有 ${wolves} 狼`
  return { seats, text }
}

/**
 * 点一个座位弹出的面板。pending 是「点了站边 / 踩人 / 报金水 / 报查杀，还在等再点一个座位」。
 * 等着选人时（picker）面板里直接给一排号码（所有座位，包括他自己）：边框颜色是我标的，出局的变淡，已经记过的亮着。
 * 面杀时最下面一排是状态：夜里出局 / 被放逐 / 被带走 / 自爆 / 当警长，记在正在看的这一天。
 * 法官房间里（room）出局和警长是自动记的，面板上没有那一排状态按钮。
 */
export function markPanel(note, no, day, pending = '', { room = false } = {}) {
  const seat = seatOf(note, no)
  const say = sayOf(note, day, no)
  const claims = note.claims && note.claims.length ? note.claims : DEFAULT_CLAIMS
  const outOn = (kind) => seat.out === kind && seat.outDay === day
  let picker = null
  if (PENDING_VERB[pending]) {
    const seats = []
    for (let other = 1; other <= note.playerCount; other += 1) {
      const target = seatOf(note, other)
      seats.push({
        no: other,
        tone: target.guess || 'none',
        gone: Boolean(target.out) && target.outDay <= day,
        on: say.tags.includes(`${TARGET_TAGS[pending]} ${other}`),
      })
    }
    picker = { title: `${no} 号${PENDING_VERB[pending]}`, seats }
  }
  return {
    no,
    title: `${no} 号`,
    picker,
    dayLabel: `第 ${day} 天他`,
    guesses: [
      ...GUESS_OPTIONS.map((item) => ({ value: item.value, label: item.label, on: seat.guess === item.value })),
      { value: '', label: '清除', on: false },
    ],
    claims: claims.map((value) => ({ value, on: seat.claim === value })),
    tags: say.tags,
    text: say.text,
    actions: [
      ...Object.keys(TARGET_TAGS).map((kind) => ({
        kind, label: { stand: '站边', step: '踩人', gold: '报金水', kill: '报查杀' }[kind], on: pending === kind,
      })),
      { kind: 'idle', label: IDLE_TAG, on: say.tags.includes(IDLE_TAG) },
      { kind: 'text', label: '+ 写一句', on: false },
    ],
    status: room ? [] : [
      { kind: 'night', label: '夜里出局', on: outOn('night') },
      { kind: 'exile', label: '被放逐', on: outOn('exile') },
      { kind: 'shot', label: '被带走', on: outOn('shot') },
      { kind: 'boom', label: '自爆', on: outOn('boom') },
      { kind: 'sheriff', label: '当警长', on: seat.sheriff },
    ],
  }
}
