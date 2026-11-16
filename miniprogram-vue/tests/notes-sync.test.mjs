import test from 'node:test'
import assert from 'node:assert/strict'
import { dayOf, encodeNote, seatOf } from '../src/utils/notes/model.js'
import { addTargetTag, seatCards, setGuess, setJudgment, toggleClaim } from '../src/utils/notes/board.js'
import { createRoomNote, roomNoteId, syncFromSnapshot } from '../src/utils/notes/sync.js'

const NOW = '2026-11-16T12:00:00.000Z'
const board = {
  id: 2, name: '预女猎白',
  roles: [
    { name: '预言家', faction: '好人', count: 1 }, { name: '女巫', faction: '好人', count: 1 }, { name: '猎人', faction: '好人', count: 1 },
    { name: '白痴', faction: '好人', count: 1 }, { name: '狼人', faction: '狼人', count: 4 }, { name: '平民', faction: '好人', count: 4 },
  ],
}
const players = Array.from({ length: 12 }, (_, index) => ({
  seatNo: index + 1, nickname: `玩家${index + 1}`, playing: true, alive: true, self: index + 1 === 10,
}))
// 第 2 天发言：首夜 7 号出局，3 号当选警长，第 1 天 5 号被放逐；第 2 夜平安夜
const snapshot = (extra = {}) => ({
  roomId: 'room_1794463517297_28ed7d4aaff4', boardId: 2, boardName: '预女猎白', playerCount: 12, roomStatus: 'playing',
  currentDay: 2, currentPhase: 'day_speech', sheriffSeatNo: 3, players,
  selfPlayer: { seatNo: 10, nickname: '玩家10' },
  rounds: [
    {
      dayNo: 1, nightDeaths: [7],
      sheriff: { candidates: [3, 5, 9], withdrew: [9], tallies: [{ targetSeatNo: 3, weight: 6, voterSeatNos: [1, 2, 4, 6, 8, 10] }], electedSeatNo: 3, lost: false },
      exile: [{
        tallies: [
          { targetSeatNo: 5, weight: 6.5, voterSeatNos: [1, 3, 6, 8, 10, 11] },
          { targetSeatNo: 3, weight: 3, voterSeatNos: [4, 5, 9] },
          { targetSeatNo: 9, weight: 2, voterSeatNos: [2, 12] },
        ],
        abstainSeatNos: [], eliminatedSeatNo: 5, pkSeatNos: [],
      }],
      skills: [],
    },
    { dayNo: 2, nightDeaths: [], sheriff: null, exile: [], skills: [] },
  ],
  ...extra,
})

test('aRoomNoteIsTiedToTheRoomAndTheSeat', () => {
  const id = roomNoteId(snapshot())
  assert.equal(id, 'rn_1794463517297_28ed7d4aaff4_s10')
  assert.ok(id.length <= 64)
  assert.equal(roomNoteId({ roomId: 'room_1', selfPlayer: {} }), '', '没入座的人没有笔记')
  const note = createRoomNote(snapshot(), board, NOW)
  assert.deepEqual([note.sessionId, note.boardMode, note.boardId, note.playerCount, note.roomId, note.wolves],
    [id, 'library', 2, 12, 'room_1794463517297_28ed7d4aaff4', 4])
  assert.deepEqual(note.claims, ['预言家', '女巫', '猎人', '白痴', '平民'])
})

test('publicFactsAreRecordedAutomatically', () => {
  // 出局、警长、票型、昨夜死讯自动记；座位用房间里的昵称
  const note = syncFromSnapshot(createRoomNote(snapshot(), board, NOW), snapshot())
  assert.equal(note.days, 2)
  assert.equal(seatOf(note, 4).name, '玩家4')
  assert.equal(seatOf(note, 3).sheriff, true)
  assert.deepEqual([seatOf(note, 7).out, seatOf(note, 7).outDay], ['night', 1])
  assert.deepEqual([seatOf(note, 5).out, seatOf(note, 5).outDay], ['exile', 1])
  assert.deepEqual(dayOf(note, 1).dead, [7])
  assert.deepEqual(dayOf(note, 1).votes, [
    { target: 5, voters: [1, 3, 6, 8, 10, 11], weight: 6.5 }, { target: 3, voters: [4, 5, 9], weight: 3 }, { target: 9, voters: [2, 12], weight: 2 },
  ], '票数记房间公布的那个数')
  assert.equal(dayOf(note, 1).exiled, 5)
  assert.deepEqual(dayOf(note, 2).dead, [])
  assert.equal(seatCards(note, 2).find((card) => card.no === 5).line, '第 1 天放逐')
})

test('syncingAgainChangesNothingAndKeepsMyMarks', () => {
  let note = syncFromSnapshot(createRoomNote(snapshot(), board, NOW), snapshot())
  assert.equal(syncFromSnapshot(note, snapshot()), note, '没有新的公开信息就原样返回，不用再存')
  note = setJudgment(addTargetTag(toggleClaim(setGuess(note, 9, 'wolf'), 9, '预言家'), 2, 4, 'stand', 9), 2, '9、4 像一起的')
  const later = syncFromSnapshot(note, snapshot({ sheriffSeatNo: 8 }))
  assert.deepEqual([seatOf(later, 9).guess, seatOf(later, 9).claim], ['wolf', '预言家'])
  assert.equal(dayOf(later, 2).judgment, '9、4 像一起的')
  assert.deepEqual([seatOf(later, 3).sheriff, seatOf(later, 8).sheriff], [false, true], '警徽移交后跟着变')
})

test('shotsExplosionsAndJudgeKillsAreRecordedToo', () => {
  const day2 = {
    dayNo: 2, nightDeaths: [2], sheriff: null,
    exile: [
      { tallies: [{ targetSeatNo: 4, weight: 2, voterSeatNos: [1, 6] }, { targetSeatNo: 9, weight: 2, voterSeatNos: [8, 11] }], abstainSeatNos: [10], eliminatedSeatNo: null, pkSeatNos: [4, 9] },
      { tallies: [{ targetSeatNo: 9, weight: 3, voterSeatNos: [1, 6, 8] }], abstainSeatNos: [10, 11], eliminatedSeatNo: 9, pkSeatNos: [] },
    ],
    skills: [
      { kind: 'hunter_shot', actorSeatNo: 9, targetSeatNo: 1 },
      { kind: 'self_destruct', actorSeatNo: 12, targetSeatNo: null },
      { kind: 'force_out', actorSeatNo: null, targetSeatNo: 6 },
      { kind: 'badge_passed', actorSeatNo: 3, targetSeatNo: 8 },
    ],
  }
  const snap = snapshot({ rounds: [snapshot().rounds[0], day2] })
  const note = syncFromSnapshot(createRoomNote(snap, board, NOW), snap)
  assert.deepEqual(dayOf(note, 2).votes, [{ target: 9, voters: [1, 6, 8], weight: 3 }], '有 PK 时记最后一轮的票')
  assert.deepEqual(dayOf(note, 2).abstain, [10, 11])
  assert.equal(dayOf(note, 2).exiled, 9)
  assert.deepEqual([seatOf(note, 2).out, seatOf(note, 9).out, seatOf(note, 1).out, seatOf(note, 12).out, seatOf(note, 6).out],
    ['night', 'exile', 'shot', 'boom', 'force'])
  assert.equal(seatCards(note, 2).find((card) => card.no === 6).line, '第 2 天被点杀')
})

test('nothingPrivateEverGetsIntoTheNote', () => {
  // 零场外：只读全场都看得到的字段。本人的身份、夜间信息、法官才有的死因和夜间记录都不能进笔记
  const leaky = snapshot({
    selfPlayer: { seatNo: 10, nickname: '玩家10', roleName: '机密预言家', faction: '机密阵营', mechanic: 'secret_seer' },
    players: players.map((player) => ({ ...player, roleName: `机密角色${player.seatNo}`, faction: '机密阵营', mechanic: 'secret' })),
    nightInfo: { checkedSeat: 9, checkResult: '机密查验结果', knifeSeat: 4, teammateSeats: [4, 5, 9], identityResult: '机密身份' },
    nightActions: [{ actorSeatNo: 9, actionType: '机密行动', targetSeatNo: 3 }],
    nightHistory: [{ dayNo: 1, actorSeatNo: 9, actionType: '机密行动', targetSeatNo: 7 }],
    dayInfo: { myVote: 5, canSelfDestruct: true },
    pending: { kind: 'hunter_shot', seatNo: 10 },
    rounds: snapshot().rounds.map((round) => ({ ...round, deathCauses: { 7: ['机密死因'] } })),
  })
  const text = JSON.stringify(encodeNote(syncFromSnapshot(createRoomNote(leaky, board, NOW), leaky), NOW))
  assert.equal(text.includes('机密'), false, text)
  assert.equal(/secret|knife|checked|teammate/i.test(text), false, text)
})

test('aLobbySnapshotChangesNothing', () => {
  const note = createRoomNote(snapshot(), board, NOW)
  assert.equal(syncFromSnapshot(note, null), note)
  assert.equal(syncFromSnapshot(note, { ...snapshot(), roomId: 'room_other' }), note, '不是这个房间的快照不并')
})

test('theSheriffElectionIsRecordedToo', () => {
  // 警长竞选的票型是全场公布的：谁上警、谁退水、谁投了谁、谁当选，自动记在那一天
  const note = syncFromSnapshot(createRoomNote(snapshot(), board, NOW), snapshot())
  assert.deepEqual(dayOf(note, 1).election, { up: [3, 5, 9], off: [9], votes: [{ target: 3, voters: [1, 2, 4, 6, 8, 10] }], elected: 3, lost: false })
  assert.deepEqual(dayOf(note, 2).election, { up: [], off: [], votes: [], elected: 0, lost: false }, '第 2 天没有竞选')

  // 还在警上发言：只知道谁上警
  const speaking = snapshot({
    currentDay: 1, currentPhase: 'sheriff_speech', sheriffSeatNo: null,
    rounds: [{ dayNo: 1, nightDeaths: [], sheriff: { candidates: [3, 5, 9], withdrew: [], tallies: [], electedSeatNo: null, lost: false }, exile: [], skills: [] }],
  })
  assert.deepEqual(dayOf(syncFromSnapshot(createRoomNote(speaking, board, NOW), speaking), 1).election,
    { up: [3, 5, 9], off: [], votes: [], elected: 0, lost: false })

  // 警徽流失
  const lost = snapshot({
    sheriffSeatNo: null,
    rounds: [{ dayNo: 1, nightDeaths: [], sheriff: { candidates: [3, 5], withdrew: [], tallies: [], electedSeatNo: null, lost: true }, exile: [], skills: [] }],
  })
  assert.equal(dayOf(syncFromSnapshot(createRoomNote(lost, board, NOW), lost), 1).election.lost, true)
})
