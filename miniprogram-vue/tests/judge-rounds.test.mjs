import test from 'node:test'
import assert from 'node:assert/strict'
import { roundRows, verdictText } from '../src/utils/judge/rounds.js'

const rounds = [
  {
    dayNo: 1,
    nightDeaths: [5],
    sheriff: { candidates: [2, 3], withdrew: [3], tallies: [{ targetSeatNo: 2, weight: 4, voterSeatNos: [1, 4, 6, 7] }], electedSeatNo: 2, lost: false },
    speechOrder: { startSeat: 3, direction: 'right', order: [3, 4, 6, 7, 1, 2] },
    exile: [
      { tallies: [{ targetSeatNo: 4, weight: 2.5, voterSeatNos: [2, 3] }, { targetSeatNo: 6, weight: 2.5, voterSeatNos: [1, 7] }], abstainSeatNos: [], eliminatedSeatNo: null, pkSeatNos: [4, 6] },
      { tallies: [{ targetSeatNo: 4, weight: 2.5, voterSeatNos: [2, 3] }], abstainSeatNos: [1, 7], eliminatedSeatNo: 4, pkSeatNos: [] },
    ],
    skills: [{ kind: 'hunter_shot', actorSeatNo: 4, targetSeatNo: 7 }],
  },
]
const nightHistory = [
  { actorSeatNo: 1, targetSeatNo: 5, actionType: 'kill', dayNo: 1 },
  { actorSeatNo: 3, targetSeatNo: null, actionType: 'none', dayNo: 1 },
  { actorSeatNo: 6, targetSeatNo: 1, actionType: 'check', dayNo: 1 },
]
const players = [
  { seatNo: 1, roleName: '狼人' }, { seatNo: 3, roleName: '女巫' }, { seatNo: 6, roleName: '预言家' },
]

test('roundsShowPublicInfoDuringTheGame', () => {
  const rows = roundRows({ roomStatus: 'playing', rounds, nightHistory, players })
  assert.equal(rows.length, 1)
  const day = rows[0]
  assert.equal(day.dayNo, 1)
  assert.equal(day.nightDeaths, '5 号')
  assert.equal(day.sheriff, '2 号当选警长（上警 2、3，退水 3）')
  assert.equal(day.speechOrder, '从 3 号开始向右 · 3 4 6 7 1 2')
  assert.equal(day.exiles.length, 2)
  assert.equal(day.exiles[0].pk, '4、6 号平票进入 PK')
  assert.equal(day.exiles[1].out, '4 号出局')
  assert.equal(day.exiles[1].abstain, '弃票 1、7')
  assert.deepEqual(day.exiles[1].tallies, ['4 号 2.5 票 ← 2、3'])
  assert.deepEqual(day.skills, ['4 号开枪带走 7 号'])
  assert.deepEqual(day.nightActions, [], '对局中不放夜间行动')
})

test('finishedGameAddsNightActions', () => {
  const rows = roundRows({ roomStatus: 'finished', rounds, nightHistory, players })
  assert.deepEqual(rows[0].nightActions, ['1 号狼人 刀 5 号', '3 号女巫 不行动', '6 号预言家 查验 1 号'])
})

test('peacefulNightAndNoSheriff', () => {
  const rows = roundRows({ roomStatus: 'playing', rounds: [{ dayNo: 2, nightDeaths: [], sheriff: null, speechOrder: null, exile: [], skills: [] }] })
  assert.equal(rows[0].nightDeaths, '平安夜')
  assert.equal(rows[0].sheriff, '')
})

test('drawShowsLiuju', () => {
  assert.deepEqual(verdictText({ roomStatus: 'finished', winnerCamp: '', winVerdict: null }), { title: '流局', reason: '对局被提前结束，不计胜负' })
  assert.deepEqual(verdictText({ roomStatus: 'finished', winnerCamp: '狼人', winVerdict: { camp: '狼人', reason: '神职全部出局，狼人阵营胜利' } }), {
    title: '狼人阵营胜利',
    reason: '神职全部出局，狼人阵营胜利',
  })
  assert.equal(verdictText({ roomStatus: 'finished', winnerCamp: '好人', winVerdict: null }).title, '好人阵营胜利')
})
