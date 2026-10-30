import test from 'node:test'
import assert from 'node:assert/strict'
import { lobbyAction, lobbySeats, rulesRows } from '../src/utils/judge/lobby.js'

const rules = { sheriffDefault: true, speechSeconds: 120, sheriffSpeechSeconds: 150, wolfSeconds: 60, roleSeconds: 30 }
function room({ mode = 'observer', host = false, self = null, players = [], count = 4, extra = {} } = {}) {
  return {
    roomStatus: 'lobby', judgeMode: mode, host, playerCount: count, rules,
    selfPlayer: self, players,
    joinedCount: players.filter((p) => p.seatNo).length,
    readyCount: players.filter((p) => p.seatNo && p.ready).length,
    canJoin: !self && players.filter((p) => p.seatNo).length < count,
    ...extra,
  }
}
const seat = (seatNo, ready, extra = {}) => ({ seatNo, nickname: `P${seatNo}`, avatar: '', ready, playing: true, ...extra })

test('lobbySeatsFillEveryChair', () => {
  const seats = lobbySeats(room({ players: [seat(2, true, { self: true })] }))
  assert.equal(seats.length, 4)
  assert.deepEqual(seats[1], { seatNo: 2, nickname: 'P2', avatar: '', ready: true, me: true, empty: false })
  assert.equal(seats[0].empty, true)
})

test('hostStartsOnlyWhenEveryoneIsReady', () => {
  const judge = { seatNo: null, judgeObserver: true, self: true }
  const notYet = lobbyAction(room({ host: true, self: judge, players: [judge, seat(1, true), seat(2, false), seat(3, true)] }))
  assert.equal(notYet.kind, 'start')
  assert.equal(notYet.disabled, true)
  assert.equal(notYet.label, '还差 2 人')
  const all = [judge, seat(1, true), seat(2, true), seat(3, true), seat(4, true)]
  const ready = lobbyAction(room({ host: true, self: judge, players: all, extra: { canStart: true } }))
  assert.deepEqual(ready, { kind: 'start', label: '开始游戏', disabled: false })
})

test('judgeHasNoReadyButton', () => {
  const judge = { seatNo: null, judgeObserver: true, self: true }
  const action = lobbyAction(room({ host: true, self: judge, players: [judge] }))
  assert.equal(action.kind, 'start')
})

test('joinedHostAlsoReadies', () => {
  const me = seat(1, false, { self: true, roomOwner: true })
  const others = [seat(2, true), seat(3, true), seat(4, true)]
  assert.equal(lobbyAction(room({ mode: 'joined', host: true, self: me, players: [me, ...others] })).kind, 'ready')
  const readyMe = { ...me, ready: true }
  const start = lobbyAction(room({ mode: 'joined', host: true, self: readyMe, players: [readyMe, ...others], extra: { canStart: true } }))
  assert.equal(start.kind, 'start')
  assert.equal(start.disabled, false)
})

test('playersReadyAndUnready', () => {
  const me = seat(3, false, { self: true })
  assert.deepEqual(lobbyAction(room({ self: me, players: [me] })), { kind: 'ready', label: '我已准备', disabled: false })
  const readyMe = { ...me, ready: true }
  assert.deepEqual(lobbyAction(room({ self: readyMe, players: [readyMe] })), { kind: 'unready', label: '取消准备', disabled: false })
  assert.equal(lobbyAction(room({ players: [seat(1, true)] })).kind, 'join')
  const full = [1, 2, 3, 4].map((n) => seat(n, true))
  assert.deepEqual(lobbyAction(room({ players: full })), { kind: 'wait', label: '房间已满', disabled: true })
})

test('onlyTheHostEditsRules', () => {
  const hostRows = rulesRows(room({ host: true }))
  assert.equal(hostRows.length, 5)
  assert.ok(hostRows.every((row) => row.editable))
  assert.deepEqual(hostRows[0], { key: 'sheriffEnabled', label: '上警竞选', value: '开', editable: true })
  assert.equal(hostRows[1].value, '120 秒')
  assert.ok(rulesRows(room({ host: false })).every((row) => !row.editable))
})
