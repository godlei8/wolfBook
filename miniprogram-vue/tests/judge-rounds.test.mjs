import test from 'node:test'
import assert from 'node:assert/strict'
import { resultCards, verdictText } from '../src/utils/judge/rounds.js'

test('drawShowsLiuju', () => {
  assert.deepEqual(verdictText({ roomStatus: 'finished', winnerCamp: '流局', winVerdict: { camp: '流局', reason: '法官结束了对局（流局）' } }), {
    title: '流局',
    reason: '法官结束了对局（流局）',
  })
  assert.deepEqual(verdictText({ roomStatus: 'finished', winnerCamp: '', winVerdict: null }), { title: '流局', reason: '对局被提前结束，不计胜负' })
  assert.deepEqual(verdictText({ roomStatus: 'finished', winnerCamp: '狼人', winVerdict: { camp: '狼人', reason: '神职全部出局，狼人阵营胜利' } }), {
    title: '狼人阵营胜利',
    reason: '神职全部出局，狼人阵营胜利',
  })
  assert.equal(verdictText({ roomStatus: 'finished', winnerCamp: '好人', winVerdict: null }).title, '好人阵营胜利')
})

test('resultCardsMarkTheWinners', () => {
  const players = [
    { seatNo: 2, nickname: 'B', roleName: '狼人', faction: '狼人', alive: false },
    { seatNo: 1, nickname: 'A', roleName: '预言家', faction: '好人', alive: true },
    { seatNo: 3, nickname: 'C', roleName: '平民', faction: '好人', alive: true },
    { seatNo: null, nickname: '法官', judgeObserver: true },
  ]
  const board = { roles: [{ name: '预言家', portrait: '/seer.png', fullIllustration: '/seer-full.png' }, { name: '狼人', portrait: '', fullIllustration: '/wolf-full.png' }] }
  const cards = resultCards({ players, winnerCamp: '好人', winVerdict: { camp: '好人' } }, board)
  assert.deepEqual(cards.map((card) => card.seatNo), [1, 2, 3])
  assert.deepEqual(cards.map((card) => card.winner), [true, false, true])
  assert.deepEqual(cards.map((card) => card.camp), ['god', 'wolf', 'villager'])
  assert.equal(cards[0].portrait, '/seer.png')
  assert.equal(cards[1].portrait, '/wolf-full.png', '没有头像用立绘')
  assert.equal(cards[2].portrait, '')
  assert.equal(cards[1].alive, false)
  const wolves = resultCards({ players, winnerCamp: '狼人', winVerdict: { camp: '狼人' } }, board)
  assert.deepEqual(wolves.map((card) => card.winner), [false, true, false])
  assert.deepEqual(resultCards({ players, winnerCamp: '流局', winVerdict: { camp: '流局' } }, board).map((card) => card.winner), [false, false, false])
})

