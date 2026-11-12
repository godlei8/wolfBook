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
  assert.equal(judge.detail, '2 号 · 开枪')
})

test('shotPanelNeverSaysHunter', () => {
  // 狼王和猎人共用开枪待办：标题、说明、按钮里都不能写「猎人」
  const shot = pendingPanel(room({ seatNo: 2 }, { kind: 'hunter_shot', seatNo: 2, options: ['use', 'skip'] }), 4)
  const judge = pendingPanel(room({ seatNo: null, judgeObserver: true }, { kind: 'hunter_shot', seatNo: 2, options: ['use', 'skip'] }, { judgeViewer: true }))
  const texts = [shot.title, shot.detail, judge.title, judge.detail, ...shot.buttons.map((button) => button.label)].filter(Boolean)
  assert.equal(texts.some((text) => text.includes('猎人')), false, texts.join(' / '))
})

test('lastWordsAreOpenToEveryone', () => {
  // 通用流程 12：遗言全场可见（currentPending）；本人「结束遗言」，房主可以「跳过」，其他人只看
  const words = { kind: 'last_words', seatNo: 3, options: ['skip'], deadline: '2026-11-12T20:02:00' }
  const own = pendingPanel(room({ seatNo: 3 }, words, { currentPending: words }))
  assert.equal(own.kind, 'last_words')
  assert.equal(own.title, '请留遗言')
  assert.deepEqual(own.buttons.map((button) => [button.decision, button.label]), [['skip', '结束遗言']])
  const host = pendingPanel(room({ seatNo: 4 }, null, { currentPending: words, host: true }))
  assert.equal(host.title, '3 号留遗言')
  assert.deepEqual(host.buttons.map((button) => [button.decision, button.label]), [['skip', '跳过']])
  const other = pendingPanel(room({ seatNo: 5 }, null, { currentPending: words }))
  assert.equal(other.kind, 'last_words')
  assert.equal(other.title, '3 号留遗言')
  assert.deepEqual(other.buttons, [])
  assert.equal(other.needsTarget, false)
})
