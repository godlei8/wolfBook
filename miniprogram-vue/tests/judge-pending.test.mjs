import test from 'node:test'
import assert from 'node:assert/strict'
import { pendingPanel } from '../src/utils/judge/pending.js'

const players = [1, 2, 3, 4, 5].map((seatNo) => ({ seatNo, nickname: `P${seatNo}`, alive: seatNo !== 5, playing: true }))
const room = (self, pending, extra = {}) => ({
  roomStatus: 'playing', currentPhase: 'skill_pending', judgeViewer: false, players, selfPlayer: self, pending, ...extra,
})

test('villagerOnlyConfirms', () => {
  const panel = pendingPanel(room({ seatNo: 3 }, { kind: 'skill_ask', seatNo: 3, options: ['skip'], deadline: null }))
  assert.equal(panel.kind, 'skill_ask')
  assert.equal(panel.needsTarget, false)
  assert.deepEqual(panel.buttons.map((button) => [button.decision, button.label]), [['skip', '确认']])
})

test('hunterChoosesToShootThenPicksATarget', () => {
  const ask = pendingPanel(room({ seatNo: 2 }, { kind: 'skill_ask', seatNo: 2, options: ['use', 'skip'] }))
  assert.deepEqual(ask.buttons.map((button) => [button.decision, button.label]), [['use', '发动技能'], ['skip', '不发动']])
  const shot = pendingPanel(room({ seatNo: 2 }, { kind: 'hunter_shot', seatNo: 2, options: ['use', 'skip'] }), 4)
  assert.equal(shot.needsTarget, true)
  assert.deepEqual(shot.targets, [1, 3, 4], '活人，不含自己')
  assert.deepEqual(shot.buttons.map((button) => [button.decision, button.label, button.target, button.disabled]), [
    ['use', '开枪带走 4 号', 4, false],
    ['skip', '不开枪', null, false],
  ])
  const noTarget = pendingPanel(room({ seatNo: 2 }, { kind: 'hunter_shot', seatNo: 2, options: ['use', 'skip'] }))
  assert.equal(noTarget.buttons[0].disabled, true)
  const badge = pendingPanel(room({ seatNo: 1 }, { kind: 'badge', seatNo: 1, options: ['use', 'skip'] }), 3)
  assert.deepEqual(badge.buttons.map((button) => button.label), ['警徽给 3 号', '撕掉警徽'])
})

test('othersOnlySeeWaiting', () => {
  const other = pendingPanel(room({ seatNo: 4 }, null))
  assert.equal(other.kind, 'waiting')
  assert.equal(other.title, '等待出局玩家确认')
  assert.equal(/\d/.test(other.title), false)
  assert.deepEqual(other.buttons, [])
  const judge = pendingPanel(room({ seatNo: null, judgeObserver: true }, { kind: 'hunter_shot', seatNo: 2, options: ['use', 'skip'] }, { judgeViewer: true }))
  assert.equal(judge.kind, 'waiting')
  assert.deepEqual(judge.buttons, [])
  assert.equal(judge.detail, '2 号 · 猎人开枪')
})
