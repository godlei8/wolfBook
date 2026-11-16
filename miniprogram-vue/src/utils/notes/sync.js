// 法官房间里的笔记（纯函数，有单测）：把房间快照里全场都看得到的信息并进笔记。
// 零场外：只读 players 的座位号和昵称、sheriffSeatNo、currentDay、rounds 里的死讯 / 票型 / 警长竞选 / 公开的技能记录。
// 本人的身份、nightInfo、dayInfo、pending、夜间记录、死因这些只给本人或法官的字段一律不读。
import { MAX_DAYS, createNote, dayOf, seatOf } from './model.js'
import { boardFacts } from './list.js'

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

/** 这个房间里我的那条笔记的 id：跟房间和座位走（开局后座位不变）。没入座返回空串。 */
export function roomNoteId(snapshot) {
  const seat = snapshot?.selfPlayer?.seatNo
  const roomId = String(snapshot?.roomId || '')
  if (!seat || !roomId) return ''
  return `rn_${roomId.replace(/^room_/, '').slice(-40)}_s${seat}`
}

/** 第一次在房间里点开笔记时建一条。 */
export function createRoomNote(snapshot, board, now) {
  return createNote({
    sessionId: roomNoteId(snapshot),
    boardMode: snapshot.boardId ? 'library' : 'custom',
    boardId: snapshot.boardId || null,
    boardName: snapshot.boardName || board?.name || '',
    playerCount: snapshot.playerCount,
    ...boardFacts(board),
    roomId: snapshot.roomId,
    now,
  })
}

// 这一天的警长竞选（全场公布的）：谁上警、谁退水、谁投了谁、谁当选。那天没有竞选就保持原样
function electionOf(round, current) {
  const sheriff = round.sheriff
  if (!sheriff) return current
  return {
    up: [...(sheriff.candidates || [])],
    off: [...(sheriff.withdrew || [])],
    votes: (sheriff.tallies || []).map((tally) => ({ target: tally.targetSeatNo, voters: [...(tally.voterSeatNos || [])] })),
    elected: sheriff.electedSeatNo || 0,
    lost: sheriff.lost === true,
  }
}

// 这一天的公开记录里，谁是怎么出局的
function outsOf(round) {
  const outs = {}
  for (const no of round.nightDeaths || []) outs[no] = 'night'
  for (const item of round.exile || []) if (item?.eliminatedSeatNo) outs[item.eliminatedSeatNo] = 'exile'
  for (const skill of round.skills || []) {
    if (skill.kind === 'hunter_shot' && skill.targetSeatNo) outs[skill.targetSeatNo] = 'shot'
    else if (skill.kind === 'self_destruct' && skill.actorSeatNo) outs[skill.actorSeatNo] = 'boom'
    else if (skill.kind === 'force_out' && (skill.targetSeatNo || skill.actorSeatNo)) outs[skill.targetSeatNo || skill.actorSeatNo] = 'force'
  }
  return outs
}

/**
 * 把快照里的公开信息并进笔记：昵称、警长、记到第几天、每天的死讯、警长竞选和放逐票型、谁怎么出局的。
 * 我自己的标记（我猜他是、他说自己是、发言摘要、判断）不动。没有新东西时原样返回同一份笔记。
 */
export function syncFromSnapshot(note, snapshot) {
  if (!note || !snapshot || snapshot.roomId !== note.roomId) return note
  const seats = { ...note.seats }
  const patch = (no, values) => {
    const current = seatOf({ ...note, seats }, no)
    const next = { ...current, ...values }
    if (!same(current, next)) seats[no] = next
  }

  for (const player of snapshot.players || []) {
    if (player?.seatNo) patch(player.seatNo, { name: String(player.nickname || '').slice(0, 20) })
  }
  for (let no = 1; no <= note.playerCount; no += 1) {
    const sheriff = snapshot.sheriffSeatNo === no
    if (seatOf({ ...note, seats }, no).sheriff !== sheriff) patch(no, { sheriff })
  }

  const dayData = { ...note.dayData }
  let days = Math.max(note.days, Math.min(MAX_DAYS, Number(snapshot.currentDay) || 1))
  for (const round of snapshot.rounds || []) {
    const day = Number(round?.dayNo)
    if (!day || day > MAX_DAYS) continue
    days = Math.max(days, day)
    const exile = round.exile || []
    const last = exile[exile.length - 1]
    const data = {
      ...dayOf(note, day),
      dead: [...(round.nightDeaths || [])],
      votes: last ? (last.tallies || []).map((tally) => ({ target: tally.targetSeatNo, voters: [...(tally.voterSeatNos || [])], weight: tally.weight })) : [],
      abstain: last ? [...(last.abstainSeatNos || [])] : [],
      exiled: exile.map((item) => item?.eliminatedSeatNo).find(Boolean) || 0,
      election: electionOf(round, dayOf(note, day).election),
    }
    if (!same(dayOf(note, day), dayOf({ ...note, dayData: { ...dayData, [day]: data } }, day))) dayData[day] = data
    for (const [no, out] of Object.entries(outsOf(round))) patch(Number(no), { out, outDay: day })
  }

  const next = { ...note, days, seats, dayData }
  const unchanged = days === note.days && same(next.seats, note.seats) && same(next.dayData, note.dayData)
  return unchanged ? note : next
}
