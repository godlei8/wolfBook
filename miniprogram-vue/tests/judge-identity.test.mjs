import test from 'node:test'
import assert from 'node:assert/strict'
import { identityCard } from '../src/utils/judge/identity.js'

const board = { roles: [{ name: '猎人', faction: '好人', fullIllustration: '/hunter.png', themeColor: '#C08A2D' }] }
const room = (self, extra = {}) => ({ roomStatus: 'playing', judgeViewer: false, selfPlayer: self, ...extra })

test('seatedPlayersSeeTheirOwnRole', () => {
  const card = identityCard(room({ seatNo: 1, roleName: '猎人', faction: '好人', alive: true }), board)
  assert.equal(card.roleName, '猎人')
  assert.equal(card.camp, '好人阵营')
  assert.equal(card.art, '/hunter.png')
  assert.equal(card.seatNo, 1)
  assert.equal(card.alive, true)
  const wolf = identityCard(room({ seatNo: 4, roleName: '狼人', faction: '狼人', alive: false }), board)
  assert.equal(wolf.camp, '狼人阵营')
  assert.equal(wolf.art, '')
  assert.equal(wolf.alive, false)
})

test('noIdentityForTheJudgeOrBeforeTheDeal', () => {
  assert.equal(identityCard(room({ seatNo: null, judgeObserver: true }, { judgeViewer: true }), board), null)
  assert.equal(identityCard(room({ seatNo: 2, roleName: null }, { roomStatus: 'lobby' }), board), null)
  assert.equal(identityCard(room(null), board), null)
})
