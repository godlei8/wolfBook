import test from 'node:test'
import assert from 'node:assert/strict'
import { lobbyAction, lobbySeats, rulesRows, seatTap, swapBanner } from '../src/utils/judge/lobby.js'

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
  assert.deepEqual(seats[1], { seatNo: 2, nickname: 'P2', avatar: '', ready: true, me: true, empty: false, requested: false })
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

// 换座（用户给定规则）：空位点了直接入座；有人的座位申请换座，对方 30 秒内同意才换
test('tappingASeatMovesOrAsksToSwap', () => {
  const me = seat(2, true, { self: true })
  const snapshot = room({ self: me, players: [seat(1, false), me] })
  assert.deepEqual(seatTap(snapshot, 3), { action: 'move', seatNo: 3, confirm: '' }, '空位直接坐')
  assert.deepEqual(seatTap(snapshot, 1), { action: 'request', seatNo: 1, confirm: '申请和 1 号 P1 换座？对方 30 秒内同意才会换。' })
  assert.equal(seatTap(snapshot, 2), null, '点自己的座位没反应')
  const asked = room({ self: me, players: [seat(1, false), me], extra: { seatSwap: { outgoing: { seatNo: 1, nickname: 'P1', deadline: '2026-11-12T20:00:30' } } } })
  assert.deepEqual(seatTap(asked, 1), { action: 'cancel', seatNo: 1, confirm: '撤回和 1 号的换座申请？' })
  assert.equal(lobbySeats(asked)[0].requested, true, '申请中的座位标出来')
  const judge = { seatNo: null, judgeObserver: true, self: true }
  assert.equal(seatTap(room({ self: judge, players: [judge, seat(1, false)] }), 2), null, '法官不入座')
  assert.equal(seatTap(room({ self: null, players: [seat(1, false)] }), 2), null, '还没进房')
})

test('swapBannerShowsIncomingFirst', () => {
  const now = Date.parse('2026-11-12T20:00:00+08:00')
  const incoming = swapBanner({ seatSwap: { incoming: { seatNo: 3, nickname: '小明', deadline: '2026-11-12T20:00:25' } } }, now)
  assert.equal(incoming.text, '3 号 小明 想和你换座')
  assert.equal(incoming.seconds, 25)
  assert.deepEqual(incoming.buttons.map((b) => [b.action, b.label, b.seatNo]), [['accept', '同意', 3], ['reject', '拒绝', 3]])
  const outgoing = swapBanner({ seatSwap: { outgoing: { seatNo: 5, nickname: 'P5', deadline: '2026-11-12T20:00:10' } } }, now)
  assert.equal(outgoing.text, '已申请和 5 号 P5 换座，等对方同意')
  assert.deepEqual(outgoing.buttons.map((b) => [b.action, b.label]), [['cancel', '撤回']])
  assert.equal(swapBanner({ seatSwap: { outgoing: { seatNo: 5, nickname: 'P5', deadline: '2026-11-12T19:59:59' } } }, now), null, '过期了就不显示')
  assert.equal(swapBanner({ seatSwap: null }, now), null)
})
