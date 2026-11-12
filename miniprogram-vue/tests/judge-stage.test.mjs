import test from 'node:test'
import assert from 'node:assert/strict'
import { fitStageHeight, gameAreaStyle, stageLayout } from '../src/utils/judge/stage.js'

const players = Array.from({ length: 12 }, (_, i) => ({
  seatNo: i + 1, nickname: `P${i + 1}`, avatar: i === 0 ? '/a.png' : '', alive: i + 1 !== 12, playing: true, roleName: i < 4 ? '狼人' : '平民', faction: i < 4 ? '狼人' : '好人',
}))
const me = (seatNo, alive = true) => ({ seatNo, alive, self: true })
const room = (phase, self, extra = {}) => ({
  roomStatus: 'playing', currentPhase: phase, currentDay: 2, judgeViewer: false, paused: false, playerCount: 12,
  players, selfPlayer: self, speech: null, dayInfo: null, pkSeats: [], rounds: [], sheriffCandidates: [], sheriffSeatNo: null, ...extra,
})
const seatsOf = (layout) => new Map(layout.sides.flatMap((side) => side.seats).map((seat) => [seat.seatNo, seat]))

test('stageKeepsTheNightColumns', () => {
  const layout = stageLayout(room('day_speech', me(1)), 'speech')
  assert.deepEqual(layout.sides.map((side) => side.key), ['left', 'right'])
  assert.deepEqual(layout.sides[0].seats.map((seat) => seat.seatNo), [1, 2, 3, 4, 5, 6])
  assert.deepEqual(layout.sides[1].seats.map((seat) => seat.seatNo), [7, 8, 9, 10, 11, 12])
  const seats = seatsOf(layout)
  assert.equal(seats.get(1).avatar, '/a.png')
  assert.equal(seats.get(2).initial, 'P')
  assert.equal(seats.get(12).dead, true)
  assert.equal(seats.get(1).dead, false)
  // 座位大小和夜里同一套算法：给了可用高度就放大
  assert.equal(stageLayout(room('day_speech', me(1)), 'speech', { stageHeight: 900 }).cardHeight > layout.cardHeight, true)
})

test('sheriffBadgeShowsInEveryPhase', () => {
  for (const [phase, view] of [['night_action', 'night'], ['day_speech', 'speech'], ['exile_vote', 'vote']]) {
    const seats = seatsOf(stageLayout(room(phase, me(1), { sheriffSeatNo: 3 }), view))
    assert.equal(seats.get(3).sheriff, true, view)
    assert.equal(seats.get(4).sheriff, false, view)
  }
})

test('speakerIsHighlighted', () => {
  const seats = seatsOf(stageLayout(room('day_speech', me(1), { speech: { order: [5, 6, 7], index: 1 } }), 'speech'))
  assert.equal(seats.get(6).speaking, true)
  assert.equal(seats.get(5).speaking, false)
  const sheriff = seatsOf(stageLayout(room('sheriff_speech', me(1), { sheriffCandidates: [2, 9], speech: { order: [9, 2], index: 0 } }), 'sheriff'))
  assert.equal(sheriff.get(9).speaking, true)
  assert.equal(sheriff.get(2).tag, '警')
  assert.equal(sheriff.get(9).tag, '警')
  assert.equal(sheriff.get(3).tag, '')
})

test('votersTapOnlyValidTargets', () => {
  const voting = room('exile_vote', me(1), { dayInfo: { canVote: true } })
  const seats = seatsOf(stageLayout(voting, 'vote', { selection: 5 }))
  assert.equal(seats.get(5).selected, true)
  assert.equal(seats.get(5).tappable, true)
  assert.equal(seats.get(12).tappable, false, '出局的人不能投')
  assert.equal(seats.get(12).dim, true)
  // PK 只能投平票的人，其余座位变暗
  const pk = seatsOf(stageLayout(room('vote_pk', me(1), { dayInfo: { canVote: true }, pkSeats: [3, 8] }), 'vote'))
  assert.deepEqual([...pk.values()].filter((seat) => seat.tappable).map((seat) => seat.seatNo), [3, 8])
  assert.equal(pk.get(3).tag, 'PK')
  assert.equal(pk.get(4).dim, true)
  // 不能投票的人：座位不能点，也不变暗
  const watcher = seatsOf(stageLayout(room('exile_vote', me(12, false), { dayInfo: { canVote: false } }), 'vote', { selection: 5 }))
  assert.equal([...watcher.values()].some((seat) => seat.tappable || seat.selected || seat.dim && !seat.dead), false)
})

test('sheriffVoteTargetsAreTheCandidates', () => {
  const snapshot = room('sheriff_vote', me(1), { sheriffCandidates: [2, 9], dayInfo: { canVote: true } })
  const seats = seatsOf(stageLayout(snapshot, 'sheriff', { selection: 9 }))
  assert.deepEqual([...seats.values()].filter((seat) => seat.tappable).map((seat) => seat.seatNo), [2, 9])
  assert.equal(seats.get(9).selected, true)
  assert.equal(seats.get(4).dim, true)
})

test('voteResultTagsTheTallies', () => {
  const rounds = [{ dayNo: 2, exile: [{ tallies: [{ targetSeatNo: 4, weight: 2.5, voterSeatNos: [1, 2] }, { targetSeatNo: 7, weight: 1, voterSeatNos: [3] }], eliminatedSeatNo: 4 }] }]
  const seats = seatsOf(stageLayout(room('vote_result', me(1), { rounds }), 'voteResult'))
  assert.equal(seats.get(4).tag, '2.5')
  assert.equal(seats.get(4).tagKind, 'votes')
  assert.equal(seats.get(4).out, true)
  assert.equal(seats.get(4).dim, false, '被放逐的人这一屏不变暗，红圈看得清')
  assert.equal(seats.get(7).tag, '1')
  assert.equal(seats.get(7).out, false)
  assert.equal(seats.get(5).tag, '')
})

test('onlyThePendingActorPicksASeat', () => {
  const pending = { kind: 'hunter_shot', seatNo: 3, options: ['use', 'skip'] }
  const actor = seatsOf(stageLayout(room('skill_pending', me(3, false), { pending }), 'pending', { selection: 7 }))
  assert.equal(actor.get(7).selected, true)
  assert.equal(actor.get(3).tappable, false, '不能带走自己')
  assert.equal(actor.get(12).tappable, false, '出局的人不能选')
  assert.equal(actor.get(5).tappable, true)
  const other = seatsOf(stageLayout(room('skill_pending', me(5), { pending }), 'pending', { selection: 7 }))
  assert.equal([...other.values()].some((seat) => seat.tappable || seat.selected || seat.speaking), false, '别人看不出谁在处理待办')
  const words = { kind: 'last_words', seatNo: 4, options: ['skip'] }
  const speaking = seatsOf(stageLayout(room('skill_pending', me(5), { currentPending: words }), 'pending'))
  assert.equal(speaking.get(4).speaking, true, '遗言公开：留遗言的人座位亮起')
  assert.equal([...speaking.values()].some((seat) => seat.tappable), false)
})

test('nightSeatsKeepWolfAndJudgeBadges', () => {
  const step = { index: 1, total: 4, kind: 'wolf', roleName: '狼人', line: '狼人请睁眼' }
  const nightInfo = { teammateSeats: [2], wolfPicks: [{ seatNo: 1, targetSeatNo: 6, decided: true }, { seatNo: 2, targetSeatNo: 6, decided: true }] }
  const wolf = seatsOf(stageLayout(room('night_action', me(1), { currentStep: step, nightInfo }), 'night', { selection: 6 }))
  assert.equal(wolf.get(2).mate, true)
  assert.equal(wolf.get(6).tag, '2')
  assert.equal(wolf.get(6).selected, true)
  assert.equal(wolf.get(6).tappable, true)
  assert.equal(wolf.get(12).tappable, false)
  // 不是这一步的人：没有任何标记、不能点
  const villager = seatsOf(stageLayout(room('night_action', me(5), { currentStep: { ...step, line: '' } }), 'night', { selection: 6 }))
  assert.equal([...villager.values()].some((seat) => seat.mate || seat.tag || seat.tappable || seat.selected), false)
  // 法官：每个座位有身份角标，白天也有
  const judge = { judgeViewer: true, selfPlayer: null }
  const judgeNight = seatsOf(stageLayout(room('night_action', null, { currentStep: step, ...judge }), 'night'))
  assert.equal(judgeNight.get(1).role, '狼')
  assert.ok(judgeNight.get(1).roleColor)
  assert.equal(seatsOf(stageLayout(room('day_speech', null, judge), 'speech')).get(5).role, '平')
  assert.equal(seatsOf(stageLayout(room('day_speech', me(1)), 'speech')).get(5).role, '', '玩家看不到身份')
})

test('seatsStayTheSameWithOrWithoutTheBar', () => {
  // 没有底部操作栏时面板往下多占操作栏那块（200rpx + 安全区 - 32rpx 底边），牌桌高度和有操作栏时一样，座位不跳
  assert.equal(gameAreaStyle(true), 'height: 380rpx')
  assert.equal(gameAreaStyle(false), 'height: calc(380rpx + var(--bar-space) - 32rpx)')
  const shared = { cardHeight: 652, current: 0, windowHeight: 844, windowWidth: 390, safeBottom: 0 }
  const rpx = 390 / 750
  const top = 600 - 380 * rpx
  const withBar = fitStageHeight({ ...shared, bar: true, bottom: top + 380 * rpx })
  const withoutBar = fitStageHeight({ ...shared, bar: false, bottom: top + (380 + 200 - 32) * rpx })
  assert.ok(Math.abs(withBar - withoutBar) <= 1, `${withBar} vs ${withoutBar}`)
})

test('fitGrowsTheStageToTheScreenBottom', () => {
  // 390 宽的手机：1px = 750/390 rpx；视图底边在 600px，屏幕 844px，有操作栏（200rpx）
  const base = { cardHeight: 652, current: 0, bottom: 600, windowHeight: 844, windowWidth: 390, bar: true, safeBottom: 0 }
  const grown = fitStageHeight(base)
  assert.equal(grown, Math.floor(652 + (844 - 200 * 390 / 750 - 600) * 750 / 390))
  // 已经贴底（差几 rpx）就不动；算出来和现在一样也不动
  assert.equal(fitStageHeight({ ...base, bottom: 844 - 200 * 390 / 750 - 1 }), null)
  assert.equal(fitStageHeight({ ...base, current: grown }), null)
  // 没有操作栏只留一点底边
  assert.ok(fitStageHeight({ ...base, bar: false }) > grown)
  // 内容超出屏幕：往回收（seatColumns 不会小于默认尺寸）
  assert.ok(fitStageHeight({ ...base, bottom: 900 }) < 652)
  assert.equal(fitStageHeight({ ...base, windowHeight: 0 }), null)
})
