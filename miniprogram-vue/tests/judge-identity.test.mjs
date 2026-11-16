import test from 'node:test'
import assert from 'node:assert/strict'
import { duelTargets, identityCard } from '../src/utils/judge/identity.js'

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

test('onlyTheSnapshotDecidesTheSelfDestructButton', () => {
  const wolf = { seatNo: 4, roleName: '狼人', faction: '狼人', alive: true }
  assert.equal(identityCard(room(wolf, { dayInfo: { canSelfDestruct: true } }), board).canSelfDestruct, true)
  assert.equal(identityCard(room(wolf, { dayInfo: { canSelfDestruct: false } }), board).canSelfDestruct, false)
  assert.equal(identityCard(room(wolf, { dayInfo: null }), board).canSelfDestruct, false)
})

test('theKnightsDuelButtonAndTargets', () => {
  // roles/骑士.md：决斗按钮和自爆一样只放在身份弹层；对象是除自己外活着的人
  const knight = { seatNo: 7, roleName: '骑士', faction: '好人', alive: true }
  const players = [{ seatNo: 1, nickname: '阿岚', alive: true }, { seatNo: 3, nickname: '', alive: false }, { seatNo: 7, nickname: '我', alive: true }, { seatNo: 9, nickname: '小鱼', alive: true }]
  const snapshot = room(knight, { dayInfo: { canDuel: true }, players })
  assert.equal(identityCard(snapshot, board).canDuel, true)
  assert.equal(identityCard(room(knight, { dayInfo: { canDuel: false }, players }), board).canDuel, false)
  assert.deepEqual(duelTargets(snapshot), [{ seatNo: 1, label: '1 号 阿岚' }, { seatNo: 9, label: '9 号 小鱼' }])
})
