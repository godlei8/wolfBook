import test from 'node:test'
import assert from 'node:assert/strict'
import { addDay, aliveSeats, createNote, dayOf, decodeNote, encodeNote, isNoteV2, seatOf } from '../src/utils/notes/model.js'

const NOW = '2026-11-16T12:00:00.000Z'
const fresh = (extra = {}) => createNote({
  sessionId: 's1', boardMode: 'library', boardId: 2, boardName: '预女猎白', playerCount: 12, wolves: 4,
  claims: ['预言家', '女巫', '猎人', '白痴', '平民'], now: NOW, ...extra,
})

test('aNewNoteStartsOnDayOneWithNothingMarked', () => {
  const note = fresh()
  assert.equal(note.days, 1)
  assert.equal(note.playerCount, 12)
  assert.deepEqual(seatOf(note, 3), { guess: '', claim: '', name: '', out: '', outDay: 0, sheriff: false })
  assert.deepEqual(dayOf(note, 1), { dead: [], votes: [], abstain: [], exiled: 0, judgment: '', peace: false, election: { up: [], off: [], votes: [], elected: 0, lost: false } })
  assert.deepEqual(aliveSeats(note, 1), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
})

test('aNoteSurvivesBeingSavedAsRecords', () => {
  // 新笔记存成 n2.* 类型的记录（内容是 JSON），后端和旧笔记的表都不用动
  const note = {
    ...fresh({ roomId: 'room_1' }),
    days: 2,
    seats: { 3: { guess: 'god', claim: '预言家', sheriff: true }, 9: { guess: 'wolf', out: 'exile', outDay: 2, name: '团子' } },
    says: { 1: { 9: { tags: ['查杀 3'], text: '' } }, 2: { 4: { tags: ['站 9', '踩 3'], text: '在帮 9 说话' } } },
    dayData: { 1: { dead: [7], votes: [{ target: 5, voters: [1, 3, 6] }], abstain: [2], exiled: 5, judgment: '4、5、9 像一起的', peace: false, election: { up: [3, 5, 9], off: [9], votes: [{ target: 3, voters: [1, 2, 4] }], elected: 3, lost: false } } },
  }
  const session = encodeNote(note, NOW)
  assert.equal(session.sessionId, 's1')
  assert.equal(session.boardMode, 'library')
  assert.equal(session.updateTime, NOW)
  assert.deepEqual([...new Set(session.records.map((record) => record.type))].sort(), ['n2.day', 'n2.meta', 'n2.say', 'n2.seat'])
  assert.ok(session.records.every((record) => record.id && record.content && record.content.length <= 5000 && record.round >= 1))
  assert.equal(new Set(session.records.map((record) => record.id)).size, session.records.length, '记录 id 不重复')
  assert.equal(isNoteV2(session), true)

  const back = decodeNote(session)
  assert.equal(back.days, 2)
  assert.equal(back.roomId, 'room_1')
  assert.equal(back.wolves, 4)
  assert.deepEqual(back.claims, ['预言家', '女巫', '猎人', '白痴', '平民'])
  assert.deepEqual(seatOf(back, 3), { guess: 'god', claim: '预言家', name: '', out: '', outDay: 0, sheriff: true })
  assert.deepEqual(seatOf(back, 9), { guess: 'wolf', claim: '', name: '团子', out: 'exile', outDay: 2, sheriff: false })
  assert.deepEqual(back.says[2][4], { tags: ['站 9', '踩 3'], text: '在帮 9 说话' })
  assert.deepEqual(dayOf(back, 1), { dead: [7], votes: [{ target: 5, voters: [1, 3, 6] }], abstain: [2], exiled: 5, judgment: '4、5、9 像一起的', peace: false, election: { up: [3, 5, 9], off: [9], votes: [{ target: 3, voters: [1, 2, 4] }], elected: 3, lost: false } })
})

test('emptyMarksAreNotSaved', () => {
  const note = { ...fresh(), seats: { 3: { guess: '' } }, says: { 1: { 3: { tags: [], text: '' } } }, dayData: { 1: {} } }
  assert.deepEqual(encodeNote(note, NOW).records.map((record) => record.type), ['n2.meta'])
})

test('oldNotesAreNotV2AndBrokenRecordsAreSkipped', () => {
  const old = { sessionId: 'o1', boardName: '旧', playerCount: 9, records: [{ id: 'r1', type: 'vote', round: 1, content: '3 投 5' }] }
  assert.equal(isNoteV2(old), false)
  assert.equal(isNoteV2(null), false)
  const broken = {
    sessionId: 'b1', boardMode: 'custom', boardName: '自定义', playerCount: 9,
    records: [
      { id: 'a', type: 'n2.meta', round: 1, content: '{"v":2,"days":3}' },
      { id: 'b', type: 'n2.seat', round: 1, player: '4', content: '{not json' },
      { id: 'c', type: 'n2.seat', round: 1, player: '99', content: '{"guess":"wolf"}' },
      { id: 'd', type: 'n2.seat', round: 1, player: '5', content: '{"guess":"alien","claim":"女巫"}' },
    ],
  }
  const note = decodeNote(broken)
  assert.equal(note.days, 3)
  assert.deepEqual(seatOf(note, 4).guess, '', '坏掉的记录跳过')
  assert.deepEqual(Object.keys(note.seats), ['5'], '超出人数的座位不要')
  assert.deepEqual(seatOf(note, 5), { guess: '', claim: '女巫', name: '', out: '', outDay: 0, sheriff: false }, '不认识的猜测当没标')
})

test('addDayStopsAtTwelve', () => {
  let note = fresh()
  note = addDay(note)
  assert.equal(note.days, 2)
  for (let i = 0; i < 20; i += 1) note = addDay(note)
  assert.equal(note.days, 12)
})

test('aliveSeatsFollowWhenSomeoneWentOut', () => {
  // 第 N 天的发言和投票：当天早上公布的夜里死者不在；当天被放逐 / 被带走 / 自爆的人还在
  const note = { ...fresh({ playerCount: 6 }), days: 2, seats: { 2: { out: 'night', outDay: 1 }, 4: { out: 'exile', outDay: 1 }, 5: { out: 'night', outDay: 2 } } }
  assert.deepEqual(aliveSeats(note, 1), [1, 3, 4, 5, 6])
  assert.deepEqual(aliveSeats(note, 2), [1, 3, 6])
})
