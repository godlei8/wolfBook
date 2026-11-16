import test from 'node:test'
import assert from 'node:assert/strict'
import { decodeNote, encodeNote, isNoteV2 } from '../src/utils/notes/model.js'
import { setGuess } from '../src/utils/notes/board.js'
import { boardFacts, newNoteSession, noteCard } from '../src/utils/notes/list.js'

const NOW = '2026-11-16T12:00:00.000Z'
const board = {
  id: 2, name: '预女猎白', playerCount: 12,
  roles: [
    { name: '预言家', faction: '好人', count: 1 }, { name: '女巫', faction: '好人', count: 1 }, { name: '猎人', faction: '好人', count: 1 },
    { name: '白痴', faction: '好人', count: 1 }, { name: '狼人', faction: '狼人', count: 4 }, { name: '平民', faction: '好人', count: 4 },
  ],
}

test('aNewNoteRemembersTheBoardsWolvesAndClaims', () => {
  assert.deepEqual(boardFacts(board), { wolves: 4, claims: ['预言家', '女巫', '猎人', '白痴', '平民'] })
  assert.deepEqual(boardFacts(null), { wolves: 0, claims: [] })
  const session = newNoteSession({
    sessionId: 's1', base: { boardMode: 'library', boardId: 2, boardName: '预女猎白', playerCount: 12 }, board, now: NOW,
  })
  assert.equal(isNoteV2(session), true)
  assert.equal(session.createTime, NOW)
  const note = decodeNote(session)
  assert.deepEqual([note.wolves, note.claims.length, note.days, note.playerCount], [4, 5, 1, 12])
  const custom = decodeNote(newNoteSession({ sessionId: 's2', base: { boardMode: 'custom', boardName: '周五局', playerCount: 9 }, board: null, now: NOW }))
  assert.deepEqual([custom.boardMode, custom.boardId, custom.wolves, custom.playerCount], ['custom', null, 0, 9])
})

test('listCardsSendNewNotesToTheBoardAndOldOnesToTheTimeline', () => {
  const session = newNoteSession({ sessionId: 's1', base: { boardMode: 'custom', boardName: '周五局', playerCount: 12 }, board, now: NOW })
  const empty = noteCard(session)
  assert.deepEqual([empty.v2, empty.url, empty.countText, empty.label], [true, '/pages/sessions/board?id=s1', '记到第 1 天', '座位盘'])
  assert.equal(empty.preview, '还没标，点进去在座位上点一点。')

  let note = setGuess(setGuess(decodeNote(session), 9, 'wolf'), 4, 'wolf')
  note = { ...setGuess(note, 3, 'god'), days: 2 }
  const marked = noteCard(encodeNote(note, NOW))
  assert.deepEqual([marked.countText, marked.label, marked.preview], ['记到第 2 天', '我的狼坑', '4、9 号'])
  const noWolves = noteCard(encodeNote(setGuess(decodeNote(session), 3, 'god'), NOW))
  assert.equal(noWolves.preview, '标了 1 个座位')

  const old = { sessionId: 'o1', records: [{ id: 'r1', type: 'vote', round: 1, content: '3 投 5' }] }
  assert.deepEqual(noteCard(old), { v2: false, url: '/pages/sessions/detail?id=o1', countText: '1 条记录', label: '投票记录', preview: '3 投 5' })
  assert.equal(noteCard({ sessionId: 'o2', records: [] }).label, '旧版笔记')
})
