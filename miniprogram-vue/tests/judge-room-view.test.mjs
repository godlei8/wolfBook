import test from 'node:test'
import assert from 'node:assert/strict'
import { countdown, parseServerTime, phaseTitle, pickView, seatColumns } from '../src/utils/judge/room-view.js'

const playing = (currentPhase, extra = {}) => ({ roomStatus: 'playing', currentPhase, currentDay: 2, ...extra })

test('pickViewFollowsStatusAndPhase', () => {
  assert.equal(pickView({ roomStatus: 'lobby', currentPhase: 'lobby' }), 'lobby')
  assert.equal(pickView({ roomStatus: 'finished', currentPhase: 'day_speech' }), 'result')
  assert.equal(pickView(playing('night_action')), 'night')
  assert.equal(pickView(playing('skill_pending')), 'pending')
  for (const phase of ['sheriff_signup', 'sheriff_speech', 'sheriff_vote', 'sheriff_pk']) {
    assert.equal(pickView(playing(phase)), 'sheriff', phase)
  }
  assert.equal(pickView(playing('day_announce')), 'announce')
  assert.equal(pickView(playing('day_speech')), 'speech')
  assert.equal(pickView(playing('exile_vote')), 'vote')
  assert.equal(pickView(playing('vote_pk')), 'vote')
  assert.equal(pickView(playing('vote_result')), 'voteResult')
  assert.equal(pickView(null), 'loading')
})

const seated = (count) => Array.from({ length: count }, (_, i) => ({ seatNo: i + 1, nickname: `P${i + 1}`, playing: true }))

test('seatsSplitLeftAndRight', () => {
  const players = [{ seatNo: null, judgeObserver: true, nickname: '法官' }, ...seated(12).reverse()]
  const columns = seatColumns(players, 12)
  assert.deepEqual(columns.left.map((seat) => seat.seatNo), [1, 2, 3, 4, 5, 6])
  assert.deepEqual(columns.right.map((seat) => seat.seatNo), [7, 8, 9, 10, 11, 12])
  assert.equal(columns.size, 92)
  assert.equal(columns.gap, 20)
  assert.equal(columns.cardHeight, 6 * 92 + 5 * 20)
  assert.equal(columns.left[0].player.nickname, 'P1')
})

test('compactSeatsForLargeBoards', () => {
  const players = seated(15).filter((player) => player.seatNo !== 10)
  const columns = seatColumns(players, 15)
  assert.deepEqual(columns.left.map((seat) => seat.seatNo), [1, 2, 3, 4, 5, 6, 7, 8])
  assert.deepEqual(columns.right.map((seat) => seat.seatNo), [9, 10, 11, 12, 13, 14, 15])
  assert.equal(columns.size, 68)
  assert.equal(columns.gap, 16)
  assert.equal(columns.cardHeight, 8 * 68 + 7 * 16)
  assert.equal(columns.right[1].empty, true)
  assert.equal(columns.right[0].empty, false)
  const eighteen = seatColumns(seated(18), 18)
  assert.equal(eighteen.left.length, 9)
  assert.equal(eighteen.size, 68)
})

test('countdownNeverGoesNegativeAndWarnsAtTen', () => {
  const now = parseServerTime('2026-10-30T20:00:00')
  assert.deepEqual(countdown('2026-10-30T20:01:05.500', now, false), { seconds: 65, text: '1:05', warn: false })
  assert.deepEqual(countdown('2026-10-30T20:00:10', now, false), { seconds: 10, text: '0:10', warn: true })
  assert.deepEqual(countdown('2026-10-30T19:59:00', now, false), { seconds: 0, text: '0:00', warn: true })
  assert.equal(countdown('2026-10-30T20:00:30', now, true).text, '暂停')
  assert.equal(countdown('2026-10-30T20:00:30', now, true).warn, false)
  assert.deepEqual(countdown(null, now, false), { seconds: 0, text: '', warn: false })
})

test('serverTimesAreBeijingTime', () => {
  assert.equal(parseServerTime('2026-10-30T20:00:00.123456789'), Date.UTC(2026, 9, 30, 12, 0, 0, 123))
  assert.equal(parseServerTime(''), null)
})

test('nightTitleIsTheSameForEveryViewer', () => {
  const base = playing('night_action', { currentStep: { index: 1, total: 4, kind: 'witch', roleName: '女巫', line: '' } })
  const holder = { ...base, selfPlayer: { seatNo: 3, roleName: '女巫', alive: true }, currentStep: { ...base.currentStep, line: '女巫请睁眼' } }
  const villager = { ...base, selfPlayer: { seatNo: 4, roleName: '平民', alive: true } }
  const dead = { ...base, selfPlayer: { seatNo: 5, roleName: '预言家', alive: false } }
  const title = phaseTitle(holder)
  assert.equal(title, '第 2 夜 · 女巫请睁眼')
  assert.equal(phaseTitle(villager), title)
  assert.equal(phaseTitle(dead), title)
  assert.equal(phaseTitle(playing('exile_vote')), '第 2 天 · 放逐投票')
})
