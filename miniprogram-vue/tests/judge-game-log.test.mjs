import test from 'node:test'
import assert from 'node:assert/strict'
import { gameLog } from '../src/utils/judge/game-log.js'

const names = ['', '阿岚', '小鱼', '老周', '木木', '七七', '北风', '阿哲', '糖糖']
const roles = ['', '狼人', '预言家', '女巫', '猎人', '平民', '平民', '平民', '狼人']
const factions = ['', '狼人', '好人', '好人', '好人', '好人', '好人', '好人', '狼人']
const mechanics = { 狼人: 'wolf', 预言家: 'seer', 女巫: 'witch', 猎人: 'hunter', 平民: 'villager' }
const dead = new Set([4, 5, 7])
const players = (finished) => names.slice(1).map((nickname, i) => ({
  seatNo: i + 1,
  nickname,
  avatar: i === 0 ? 'https://x/1.png' : '',
  alive: !dead.has(i + 1),
  self: i + 1 === 6,
  roleName: finished || i + 1 === 6 ? roles[i + 1] : null,
  faction: finished || i + 1 === 6 ? factions[i + 1] : null,
  mechanic: finished ? mechanics[roles[i + 1]] : undefined,
}))
const rounds = (finished) => [
  {
    dayNo: 1,
    nightDeaths: [5],
    deathCauses: finished ? { 5: ['knife'] } : {},
    sheriff: { candidates: [2, 3], withdrew: [3], tallies: [{ targetSeatNo: 2, weight: 4, voterSeatNos: [1, 4, 6, 7] }], electedSeatNo: 2, lost: false },
    speechOrder: { startSeat: 3, direction: 'right', order: [3, 4, 6, 7, 1, 2] },
    exile: [
      { tallies: [{ targetSeatNo: 6, weight: 2, voterSeatNos: [1, 7] }, { targetSeatNo: 4, weight: 2.5, voterSeatNos: [2, 3] }], abstainSeatNos: [], eliminatedSeatNo: null, pkSeatNos: [4, 6] },
      { tallies: [{ targetSeatNo: 4, weight: 2.5, voterSeatNos: [2, 3] }], abstainSeatNos: [1, 7], eliminatedSeatNo: 4, pkSeatNos: [] },
    ],
    skills: [{ kind: 'hunter_shot', actorSeatNo: 4, targetSeatNo: 7 }, { kind: 'badge_passed', actorSeatNo: 2, targetSeatNo: null }],
  },
  { dayNo: 2, nightDeaths: [], deathCauses: {}, sheriff: null, speechOrder: null, exile: [], skills: [] },
]
const nightHistory = [
  { actorSeatNo: 1, targetSeatNo: 5, actionType: 'kill', dayNo: 1 },
  { actorSeatNo: 8, targetSeatNo: 5, actionType: '刀人', dayNo: 1 },
  { actorSeatNo: 3, targetSeatNo: null, actionType: 'none', dayNo: 1 },
  { actorSeatNo: 2, targetSeatNo: 1, actionType: 'check', dayNo: 1 },
  { actorSeatNo: 4, targetSeatNo: null, actionType: 'confirm', dayNo: 1 },
  { actorSeatNo: 1, targetSeatNo: null, actionType: 'none', dayNo: 2 },
  { actorSeatNo: 8, targetSeatNo: 2, actionType: 'kill', dayNo: 2 },
]
const playing = () => ({ roomStatus: 'playing', players: players(false), rounds: rounds(false), nightHistory: [] })
const finished = () => ({ roomStatus: 'finished', players: players(true), rounds: rounds(true), nightHistory, winVerdict: { camp: '好人', reason: '狼人全部出局' } })
const seatsOf = (list) => list.map((person) => person.seatNo)

test('duringTheGameTheLogOnlyHasPublicInfo', () => {
  const log = gameLog(playing())
  assert.equal(log.verdict, null)
  const [day1, day2] = log.days
  assert.equal(day1.night.title, '第 1 夜')
  assert.equal(day1.night.result, '1 人出局')
  assert.deepEqual(day1.night.deaths.map((death) => [death.person.seatNo, death.role, death.cause]), [[5, '', '']])
  assert.deepEqual(day1.night.actions, [], '对局中没有夜间行动')
  assert.equal(day2.night.result, '平安夜')
  assert.equal(day2.day, null, '这一天还没有白天的记录')
})

test('logNeverListsTheSpeechOrder', () => {
  // 用户给定：对局记录不记发言顺序
  assert.equal(JSON.stringify(gameLog(finished())).includes('order'), false)
})

test('dayShowsSheriffVotesAndEventsWithPeople', () => {
  const day = gameLog(playing()).days[0].day
  assert.equal(day.title, '第 1 天')
  assert.equal(day.result, '4 号被放逐')
  assert.deepEqual(day.sheriff.candidates.map((c) => [c.person.seatNo, c.withdrew, c.elected]), [[2, false, true], [3, true, false]])
  assert.equal(day.sheriff.result, '2 号当选 · 4 票')
  const [first, pk] = day.votes
  assert.equal(first.label, '放逐投票')
  assert.equal(first.result, '4、6 号平票，进入 PK')
  assert.deepEqual(first.bars.map((bar) => [bar.person.seatNo, bar.count, bar.pct, bar.top]), [[4, '2.5 票', 100, true], [6, '2 票', 80, false]])
  assert.deepEqual(seatsOf(first.bars[0].voters), [2, 3])
  assert.equal(pk.label, 'PK 投票')
  assert.equal(pk.result, '4 号出局')
  assert.deepEqual(seatsOf(pk.abstain), [1, 7])
  assert.deepEqual(day.events.map((e) => [e.tag, e.a.seatNo, e.text, e.b ? e.b.seatNo : null]), [
    ['技能', 4, '发动技能，带走了', 7],
    ['警徽', 2, '撕掉了警徽', null],
  ])
})

test('peopleCarryAvatarOrInitial', () => {
  const day = gameLog(playing()).days[0].day
  const [first] = day.votes[0].bars[0].voters
  assert.equal(first.seatNo, 2)
  assert.equal(first.initial, '小')
  assert.equal(first.avatar, '')
  assert.match(first.color, /^#[0-9a-f]{6}$/i)
  assert.equal(day.votes[1].abstain[0].avatar, 'https://x/1.png')
})

test('finishedLogAddsNightActionsCausesAndRoles', () => {
  const log = gameLog(finished())
  assert.deepEqual(log.verdict, { title: '好人阵营胜利', reason: '狼人全部出局', camp: 'good', days: '共 2 天' })
  const night = log.days[0].night
  assert.deepEqual(night.deaths.map((death) => [death.person.seatNo, death.role, death.cause]), [[5, '平民', '被刀']])
  assert.deepEqual(night.actions.map((a) => [seatsOf(a.actors), a.role, a.camp, a.verb, a.target ? a.target.seatNo : null, a.note]), [
    [[1, 8], '狼人', 'wolf', '刀', 5, '2 票'],
    [[3], '女巫', 'god', '不用药', null, ''],
    [[2], '预言家', 'god', '验', 1, '狼人'],
  ])
  assert.deepEqual(log.days[1].night.actions.map((a) => [seatsOf(a.actors), a.verb, a.target ? a.target.seatNo : null]), [
    [[1], '空刀', null],
    [[8], '刀', 2],
  ])
})

test('byPlayerTellsEachPersonsGame', () => {
  const people = gameLog(finished()).people
  assert.deepEqual(people.map((p) => p.person.seatNo), [1, 2, 3, 4, 5, 6, 7, 8])
  const bySeat = new Map(people.map((p) => [p.person.seatNo, p]))
  assert.equal(bySeat.get(1).role, '狼人')
  assert.equal(bySeat.get(1).camp, 'wolf')
  assert.equal(bySeat.get(1).story, '刀 5 号 · 投 6 号')
  assert.equal(bySeat.get(1).fate, '存活')
  assert.equal(bySeat.get(2).story, '当选警长 · 验 1 号 · 投 4 号 · 撕掉警徽')
  assert.equal(bySeat.get(3).story, '上警后退水 · 投 4 号')
  assert.equal(bySeat.get(4).story, '带走 7 号 · 第 1 天被放逐')
  assert.equal(bySeat.get(4).fate, '第 1 天')
  assert.equal(bySeat.get(4).dead, true)
  assert.equal(bySeat.get(5).story, '第 1 夜被刀')
  assert.equal(bySeat.get(5).fate, '第 1 夜')
  assert.equal(bySeat.get(7).story, '投 6 号 · 被 4 号带走')
  assert.equal(bySeat.get(6).me, true)
  assert.equal(bySeat.get(8).story, '刀 5、2 号')
})

test('byPlayerDuringTheGameHidesOtherRoles', () => {
  const people = gameLog(playing()).people
  const bySeat = new Map(people.map((p) => [p.person.seatNo, p]))
  assert.equal(bySeat.get(1).role, '')
  assert.equal(bySeat.get(1).camp, null)
  assert.equal(bySeat.get(1).story, '投 6 号')
  assert.equal(bySeat.get(6).role, '平民', '自己的身份自己看得到')
  assert.equal(bySeat.get(5).story, '第 1 夜出局')
})

test('selfDestructIsADayEvent', () => {
  const log = gameLog({ roomStatus: 'playing', players: [], rounds: [{ dayNo: 1, nightDeaths: [], skills: [{ kind: 'self_destruct', actorSeatNo: 5, targetSeatNo: null }] }] })
  assert.equal(log.days[0].day.result, '5 号自爆')
  assert.deepEqual(log.days[0].day.events.map((e) => [e.tag, e.a.seatNo, e.text]), [['自爆', 5, '自爆']])
})

test('mechWolfNightActionsReadInTheRecord', () => {
  const log = gameLog({
    roomStatus: 'finished',
    players: [{ seatNo: 6, roleName: '机械狼', faction: '狼人', mechanic: 'mech_wolf' }, { seatNo: 2, roleName: '女巫', faction: '好人' }],
    rounds: [{ dayNo: 1, nightDeaths: [], exile: [], skills: [] }],
    nightHistory: [
      { dayNo: 1, actorSeatNo: 6, actionType: 'learn', targetSeatNo: 2 },
      { dayNo: 1, actorSeatNo: 6, actionType: 'kill_pierce', targetSeatNo: 3 },
    ],
  })
  assert.deepEqual(log.days[0].night.actions.map((a) => [a.role, a.verb, a.target.seatNo, a.note]), [
    ['机械狼', '学习', 2, '学到女巫'],
    ['机械狼', '破盾刀', 3, ''],
  ])
  assert.equal(log.people.find((p) => p.person.seatNo === 6).story, '学 2 号 · 破盾刀 3 号')
})
