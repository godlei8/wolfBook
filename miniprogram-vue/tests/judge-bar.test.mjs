import test from 'node:test'
import assert from 'node:assert/strict'
import { godSeats, judgeActions, pausedOverlay, tonightRows } from '../src/utils/judge/judge-bar.js'

const players = [
  { seatNo: 1, roleName: '狼人', faction: '狼人', alive: true, playing: true },
  { seatNo: 2, roleName: '狼人', faction: '狼人', alive: true, playing: true },
  { seatNo: 3, roleName: '女巫', faction: '好人', alive: true, playing: true },
  { seatNo: 4, roleName: '预言家', faction: '好人', alive: true, playing: true },
  { seatNo: 5, roleName: '平民', faction: '好人', alive: true, playing: true },
  { seatNo: 6, roleName: '平民', faction: '好人', alive: false, playing: true },
  { seatNo: null, judgeObserver: true, playing: false },
]

function room(extra = {}) {
  return {
    roomStatus: 'playing', currentPhase: 'night_action', currentDay: 1, judgeViewer: true, host: true, paused: false,
    currentStep: { index: 1, total: 3, kind: 'witch', roleName: '女巫', line: '女巫请睁眼' },
    players,
    nightActions: [
      { actorSeatNo: 1, targetSeatNo: 5, actionType: 'kill', dayNo: 1 },
      { actorSeatNo: 2, targetSeatNo: 5, actionType: 'kill', dayNo: 1 },
      { actorSeatNo: 3, targetSeatNo: 5, actionType: 'save', dayNo: 1 },
    ],
    ...extra,
  }
}

test('onlyTheJudgeSeesRoles', () => {
  assert.equal(godSeats(room({ judgeViewer: false })).size, 0)
  const seats = godSeats(room())
  assert.equal(seats.get(1).roleShort, '狼')
  assert.equal(seats.get(3).roleShort, '女')
  assert.notEqual(seats.get(1).roleColor, seats.get(3).roleColor)
  assert.equal(seats.get(5).votes, 2, '狼刀票数')
  assert.equal(seats.has(null), false)
})

test('tonightRowsSummariseTheNight', () => {
  const rows = tonightRows(room())
  const wolves = rows.find((row) => row.role === '狼人')
  assert.deepEqual(wolves.seats, [1, 2])
  assert.equal(wolves.state, '1→5 2→5 · 刀口 5 号')
  assert.equal(rows.find((row) => row.role === '女巫').state, '救 5 号')
  assert.equal(rows.find((row) => row.role === '预言家').state, '还没轮到')
  assert.equal(rows.some((row) => row.role === '平民'), false)
  const undecided = tonightRows(room({ nightActions: [{ actorSeatNo: 1, targetSeatNo: null, actionType: 'none', dayNo: 1 }], currentStep: { index: 0, kind: 'wolf', roleName: '狼人' } }))
  assert.equal(undecided.find((row) => row.role === '狼人').state, '1→空刀 2→? · 刀口 空刀')
  assert.equal(undecided.find((row) => row.role === '女巫').state, '还没轮到')
  assert.deepEqual(tonightRows(room({ judgeViewer: false })), [])
})

test('pausedRoomOffersResumeOnly', () => {
  assert.deepEqual(judgeActions(room()), { pause: true, resume: false, forceOut: true })
  assert.deepEqual(judgeActions(room({ paused: true })), { pause: false, resume: true, forceOut: true })
  assert.deepEqual(judgeActions(room({ judgeViewer: false })), { pause: false, resume: false, forceOut: false })
  assert.deepEqual(judgeActions(room({ roomStatus: 'finished' })), { pause: false, resume: false, forceOut: false })
})

test('everyoneGetsTheSamePauseOverlay', () => {
  assert.equal(pausedOverlay(room()), null)
  const judge = pausedOverlay(room({ paused: true }))
  const player = pausedOverlay(room({ paused: true, judgeViewer: false, host: false }))
  assert.deepEqual(judge, player)
  assert.equal(judge.title, '法官暂停')
})

test('judgeSummaryBeforeWolvesChoose', () => {
  const rows = tonightRows(room({ nightActions: [], currentStep: { index: 0, kind: 'wolf', roleName: '狼人' } }))
  assert.equal(rows.find((row) => row.role === '狼人').state, '1→? 2→? · 刀口 还没定')
})

test('tonightRowsFollowTheBoardNightOrder', () => {
  // 板子把预言家排在狼前面：狼队行动时预言家已经轮过了，女巫还没到
  const board = { ruleDetail: { nightOrder: [{ roleName: '预言家', faction: '好人' }, { roleName: '狼人', faction: '狼人' }, { roleName: '女巫', faction: '好人' }] } }
  const snapshot = room({ nightActions: [], currentStep: { index: 1, kind: 'wolf', roleName: '狼人' } })
  const rows = tonightRows(snapshot, board)
  assert.equal(rows.find((row) => row.role === '预言家').state, '没有行动')
  assert.equal(rows.find((row) => row.role === '女巫').state, '还没轮到')
  assert.equal(tonightRows(snapshot).find((row) => row.role === '预言家').state, '还没轮到', '没有板子时按通用顺序')
})
