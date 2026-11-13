import test from 'node:test'
import assert from 'node:assert/strict'
import { godSeats, hostMenu, judgeActions, pausedOverlay, tonightRows } from '../src/utils/judge/judge-bar.js'

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

test('judgeAndJoinedHostControls', () => {
  // 用户给定：法官暂停 / 继续、点杀、提前结束；无法官房主只有暂停 / 继续和提前结束
  assert.deepEqual(judgeActions(room()), { pause: true, resume: false, forceOut: true, finish: true })
  assert.deepEqual(judgeActions(room({ paused: true })), { pause: false, resume: true, forceOut: true, finish: true })
  const none = { pause: false, resume: false, forceOut: false, finish: false }
  assert.deepEqual(judgeActions(room({ judgeViewer: false })), none)
  assert.deepEqual(judgeActions(room({ roomStatus: 'finished' })), none)
  const joinedHost = room({ judgeViewer: false, judgeMode: 'joined', host: true })
  assert.deepEqual(judgeActions(joinedHost), { pause: true, resume: false, forceOut: false, finish: true })
  assert.deepEqual(judgeActions(room({ judgeViewer: false, judgeMode: 'joined', host: false })), none, '无法官的普通玩家没有')
})

test('everyoneGetsTheSamePauseOverlay', () => {
  assert.equal(pausedOverlay(room()), null)
  const judge = pausedOverlay(room({ paused: true }))
  const player = pausedOverlay(room({ paused: true, judgeViewer: false, host: false }))
  assert.deepEqual([judge.title, judge.hint], [player.title, player.hint], '所有人看到同一张遮罩')
  assert.equal(judge.title, '对局暂停')
  assert.equal(judge.hint, '游戏已暂停，等法官继续')
  assert.equal(pausedOverlay(room({ paused: true, judgeMode: 'joined' })).hint, '游戏已暂停，等房主继续')
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

test('judgeSeesTheMechWolfInItsOwnRow', async () => {
  const { tonightRows } = await import('../src/utils/judge/judge-bar.js')
  const snapshot = {
    judgeViewer: true, currentDay: 2, currentPhase: 'night_action', currentStep: { index: 3, kind: 'witch', roleName: '女巫' },
    players: [
      { seatNo: 1, roleName: '狼人', faction: '狼人', mechanic: 'wolf', alive: true },
      { seatNo: 6, roleName: '机械狼', faction: '狼人', mechanic: 'mech_wolf', alive: true },
      { seatNo: 2, roleName: '女巫', faction: '好人', mechanic: 'witch', alive: true },
    ],
    nightActions: [
      { dayNo: 2, actorSeatNo: 1, actionType: 'kill', targetSeatNo: 2 },
      { dayNo: 2, actorSeatNo: 6, actionType: 'learn', targetSeatNo: 2 },
      { dayNo: 2, actorSeatNo: 6, actionType: 'poison', targetSeatNo: 1 },
    ],
  }
  const rows = tonightRows(snapshot)
  const wolf = rows.find((row) => row.role === '狼人')
  assert.deepEqual(wolf.seats, [1], '机械狼不在狼队那一行')
  const mech = rows.find((row) => row.role === '机械狼')
  assert.equal(mech.state, '学习 2 号 · 毒 1 号')
})

test('joinedHostControlsLiveInTheSettingsMenu', () => {
  // 用户给定：无法官房主的暂停游戏、提前结束放进右上方的设置菜单，不占底部；播报开关还在顶栏，不进菜单
  const host = room({ judgeViewer: false, judgeMode: 'joined', host: true })
  assert.deepEqual(hostMenu(host).map((item) => [item.key, item.label]), [
    ['pause', '暂停游戏'],
    ['finish', '提前结束'],
  ])
  assert.deepEqual(hostMenu({ ...host, paused: true }).map((item) => item.key), ['resume', 'finish'])
  assert.deepEqual(hostMenu(room({ judgeViewer: false, judgeMode: 'joined', host: false })), [], '普通玩家没有')
  assert.deepEqual(hostMenu(room()).map((item) => [item.key, item.label]), [['finish', '提前结束']], '法官：暂停在底部，提前结束在菜单里')
  assert.deepEqual(hostMenu({ ...host, roomStatus: 'lobby' }), [], '大厅里没有设置菜单')
})

test('thePauseOverlayLetsTheControllerResume', () => {
  assert.equal(pausedOverlay(room({ paused: true, judgeViewer: false, judgeMode: 'joined', host: true })).canResume, true)
  assert.equal(pausedOverlay(room({ paused: true })).canResume, true, '法官')
  assert.equal(pausedOverlay(room({ paused: true, judgeViewer: false, host: false })).canResume, false)
})
