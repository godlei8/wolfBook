// 对局笔记（新版）的数据：内存里的笔记状态 ↔ 接口里的「会话 + 记录」。纯函数，有单测。
// 新笔记存成一组 n2.* 类型的记录，内容是 JSON，后端不用动（设计稿 docs/superpowers/specs/2026-11-16-session-notes-v2-design.md）：
//   n2.meta 版本、记到第几天、关联的法官房间、板子有几只狼、能跳哪些身份
//   n2.seat 一个座位整局不变的标记：我猜他是、他说自己是、昵称、怎么出局的（夜里 / 放逐 / 被带走 / 自爆 / 被点杀）、是不是警长
//   n2.say  某一天某个座位的发言摘要：快捷标记和一句话
//   n2.day  某一天的死讯、票型、弃票、被放逐的人、我的判断；这一天之前那一夜是不是平安夜；
//           这一天的警长竞选（election）：谁上警、谁退水、谁投了谁、谁当选、是不是警徽流失
// 没有 n2.meta 的是旧笔记（查验 / 投票 / 发言 / 狼坑的时间线），不转换，还用旧的详情页。

export const NOTE_VERSION = 2
export const MAX_DAYS = 12
const TYPE = { meta: 'n2.meta', seat: 'n2.seat', say: 'n2.say', day: 'n2.day' }
const GUESSES = ['wolf', 'good', 'god']
const OUTS = ['night', 'exile', 'shot', 'boom', 'force']

const EMPTY_SEAT = { guess: '', claim: '', name: '', out: '', outDay: 0, sheriff: false }
const EMPTY_ELECTION = { up: [], off: [], votes: [], elected: 0, lost: false }
const EMPTY_DAY = { dead: [], votes: [], abstain: [], exiled: 0, judgment: '', peace: false, election: EMPTY_ELECTION }

const text = (value, max) => (typeof value === 'string' ? value.trim().slice(0, max) : '')
const seatNo = (value, playerCount) => {
  const no = Number(value)
  return Number.isInteger(no) && no >= 1 && no <= playerCount ? no : 0
}
const seatList = (value, playerCount) => [...new Set((Array.isArray(value) ? value : []).map((item) => seatNo(item, playerCount)).filter(Boolean))]

function cleanSeat(raw, playerCount) {
  const source = raw && typeof raw === 'object' ? raw : {}
  const out = OUTS.includes(source.out) ? source.out : ''
  const outDay = out ? Math.max(1, Math.min(MAX_DAYS, Number(source.outDay) || 1)) : 0
  return {
    guess: GUESSES.includes(source.guess) ? source.guess : '',
    claim: text(source.claim, 12),
    name: text(source.name, 20),
    out,
    outDay,
    sheriff: source.sheriff === true,
  }
}

function cleanSay(raw) {
  const source = raw && typeof raw === 'object' ? raw : {}
  const tags = [...new Set((Array.isArray(source.tags) ? source.tags : []).map((tag) => text(tag, 16)).filter(Boolean))].slice(0, 8)
  return { tags, text: text(source.text, 60) }
}

// 票型：一组 = 被投的人 + 投他的人。放逐票另记当时的票数（weight，警长 1.5 票），之后警徽换人也不变
function cleanGroups(value, playerCount, weighted = false) {
  return (Array.isArray(value) ? value : [])
    .map((group) => {
      const clean = { target: seatNo(group?.target, playerCount), voters: seatList(group?.voters, playerCount) }
      const weight = Number(group?.weight)
      if (weighted && weight > 0) clean.weight = weight
      return clean
    })
    .filter((group) => group.target && group.voters.length)
}

function cleanElection(raw, playerCount) {
  const source = raw && typeof raw === 'object' ? raw : {}
  return {
    up: seatList(source.up, playerCount),
    off: seatList(source.off, playerCount),
    votes: cleanGroups(source.votes, playerCount),
    elected: seatNo(source.elected, playerCount),
    lost: source.lost === true,
  }
}

function cleanDay(raw, playerCount) {
  const source = raw && typeof raw === 'object' ? raw : {}
  return {
    dead: seatList(source.dead, playerCount),
    votes: cleanGroups(source.votes, playerCount, true),
    abstain: seatList(source.abstain, playerCount),
    exiled: seatNo(source.exiled, playerCount),
    judgment: text(source.judgment, 200),
    peace: source.peace === true,
    election: cleanElection(source.election, playerCount),
  }
}

const sameAs = (value, empty) => JSON.stringify(value) === JSON.stringify(empty)

/** 一个座位的标记（没标过的补默认值）。 */
export function seatOf(note, no) {
  return cleanSeat(note?.seats?.[no], note?.playerCount || 0)
}

/** 某一天的死讯、票型、判断（没记过的补默认值）。 */
export function dayOf(note, day) {
  return cleanDay(note?.dayData?.[day], note?.playerCount || 0)
}

/** 某一天某个座位的发言摘要。 */
export function sayOf(note, day, no) {
  return cleanSay(note?.says?.[day]?.[no])
}

/**
 * 第 day 天发言和投票时还在场的座位：当天早上公布的夜里死者不在；
 * 当天被放逐、被带走、自爆的人那时还在（他们是白天才出局的）。
 */
export function aliveSeats(note, day) {
  const seats = []
  for (let no = 1; no <= (note?.playerCount || 0); no += 1) {
    const seat = seatOf(note, no)
    const gone = seat.out && (seat.outDay < day || (seat.outDay === day && seat.out === 'night'))
    if (!gone) seats.push(no)
  }
  return seats
}

/** 新建一条笔记。wolves 是板子里狼人阵营的人数（不知道填 0），claims 是这个板子能跳的身份。 */
export function createNote({ sessionId, boardMode, boardId, boardName, playerCount, wolves = 0, claims = [], roomId = '', now }) {
  return {
    sessionId,
    boardMode: boardMode === 'library' ? 'library' : 'custom',
    boardId: boardMode === 'library' ? boardId || null : null,
    boardName: boardName || '',
    playerCount: Math.max(1, Math.min(20, Number(playerCount) || 12)),
    createTime: now,
    updateTime: now,
    days: 1,
    roomId: roomId || '',
    wolves: Math.max(0, Number(wolves) || 0),
    claims: (Array.isArray(claims) ? claims : []).map((claim) => text(claim, 12)).filter(Boolean),
    seats: {},
    says: {},
    dayData: {},
  }
}

export function isNoteV2(session) {
  return Boolean(session && Array.isArray(session.records) && session.records.some((record) => record?.type === TYPE.meta))
}

function parse(content) {
  try {
    const value = JSON.parse(content)
    return value && typeof value === 'object' ? value : null
  } catch (error) {
    return null
  }
}

/** 接口里的会话 → 笔记状态。读不懂的记录跳过。 */
export function decodeNote(session) {
  const note = createNote({
    sessionId: session.sessionId,
    boardMode: session.boardMode,
    boardId: session.boardId,
    boardName: session.boardName,
    playerCount: session.playerCount,
    now: session.createTime,
  })
  note.updateTime = session.updateTime || session.createTime
  for (const record of session.records || []) {
    const data = parse(record?.content)
    if (!data) continue
    const day = Math.max(1, Math.min(MAX_DAYS, Number(record.round) || 1))
    const no = seatNo(record.player, note.playerCount)
    if (record.type === TYPE.meta) {
      note.days = Math.max(1, Math.min(MAX_DAYS, Number(data.days) || 1))
      note.roomId = text(data.roomId, 64)
      note.wolves = Math.max(0, Number(data.wolves) || 0)
      note.claims = (Array.isArray(data.claims) ? data.claims : []).map((claim) => text(claim, 12)).filter(Boolean)
    } else if (record.type === TYPE.seat && no) {
      note.seats[no] = cleanSeat(data, note.playerCount)
    } else if (record.type === TYPE.say && no) {
      note.says[day] = { ...(note.says[day] || {}), [no]: cleanSay(data) }
    } else if (record.type === TYPE.day) {
      note.dayData[day] = cleanDay(data, note.playerCount)
    }
  }
  return note
}

/** 笔记状态 → 接口里的会话（整份保存）。没标过的座位、空的那一天不存。 */
export function encodeNote(note, now) {
  const id = (suffix) => `${note.sessionId}:${suffix}`
  const record = (suffix, type, round, player, data) => ({
    id: id(suffix), type, round, player: player ? String(player) : '', content: JSON.stringify(data), timestamp: now,
  })
  const records = [
    record('meta', TYPE.meta, 1, '', { v: NOTE_VERSION, days: note.days, roomId: note.roomId || '', wolves: note.wolves || 0, claims: note.claims || [] }),
  ]
  for (let no = 1; no <= note.playerCount; no += 1) {
    const seat = seatOf(note, no)
    if (!sameAs(seat, EMPTY_SEAT)) records.push(record(`seat:${no}`, TYPE.seat, 1, no, seat))
  }
  for (let day = 1; day <= note.days; day += 1) {
    for (let no = 1; no <= note.playerCount; no += 1) {
      const say = sayOf(note, day, no)
      if (say.tags.length || say.text) records.push(record(`say:${day}:${no}`, TYPE.say, day, no, say))
    }
    const data = dayOf(note, day)
    if (!sameAs(data, EMPTY_DAY)) records.push(record(`day:${day}`, TYPE.day, day, '', data))
  }
  return {
    sessionId: note.sessionId,
    boardMode: note.boardMode,
    boardId: note.boardId,
    boardName: note.boardName,
    playerCount: note.playerCount,
    createTime: note.createTime,
    updateTime: now,
    records,
  }
}

/** 记到下一天（最多 12 天）。 */
export function addDay(note) {
  return { ...note, days: Math.min(MAX_DAYS, (note.days || 1) + 1) }
}
