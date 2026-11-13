import test from 'node:test'
import assert from 'node:assert/strict'
import { judgeNightAlert, judgeRecap, judgeSeatMarks, judgeSeatSheet, judgeVotePanel } from '../src/utils/judge/judge-desk.js'

// 法官视角（用户给定：以玩家的牌桌为主，叠加法官的功能）：座位上的标记、面板、点座位的菜单。
const ROLES = { 1: '狼人', 3: '狼人', 12: '狼人', 4: '女巫', 7: '猎人', 8: '预言家', 9: '白痴' }
const players = Array.from({ length: 12 }, (_, i) => {
  const seatNo = i + 1
  const roleName = ROLES[seatNo] || '平民'
  return {
    seatNo, nickname: `P${seatNo}`, playing: true, alive: ![10, 11].includes(seatNo),
    roleName, faction: roleName === '狼人' ? '狼人' : '好人', mechanic: roleName === '狼人' ? 'wolf' : '',
  }
})
const room = (phase, extra = {}) => ({
  roomStatus: 'playing', currentPhase: phase, currentDay: 2, judgeViewer: true, playerCount: 12, players,
  sheriffSeatNo: 2, speech: null, nightActions: [], nightHistory: [], voteTallies: [], pkSeats: [], rounds: [], ...extra,
})
const kill = (actorSeatNo, targetSeatNo) => ({ actorSeatNo, targetSeatNo, actionType: 'kill', dayNo: 2 })

test('nightMarksShowKnifePicksTheTargetAndWhoIsActing', () => {
  const snapshot = room('night_action', { currentStep: { kind: 'witch', roleName: '女巫' }, nightActions: [kill(1, 5), kill(3, 5), kill(12, 6)] })
  const marks = judgeSeatMarks(snapshot, 'night')
  assert.deepEqual([marks.get(1).tag, marks.get(1).tagKind], ['刀5', 'dark'])
  assert.equal(marks.get(12).tag, '刀6')
  assert.deepEqual([marks.get(5).tag, marks.get(5).tagKind, marks.get(5).ring], ['刀口', 'red', 'target'])
  assert.equal(marks.get(4).ring, 'act', '这一步的持有者亮起来')
  assert.equal(judgeNightAlert(snapshot), '狼队没统一：按多数刀 5 号')
})

test('aKnifeTieIsRandomAndHasNoTarget', () => {
  const snapshot = room('night_action', { currentStep: { kind: 'wolf', roleName: '狼人' }, nightActions: [kill(1, 5), kill(3, 6)] })
  const marks = judgeSeatMarks(snapshot, 'night')
  assert.equal(marks.get(5)?.ring, undefined)
  assert.equal(marks.get(12).ring, 'act', '狼人那一步所有活狼亮起来')
  assert.equal(judgeNightAlert(snapshot), '狼队平票：到点在 5、6 号里随机')
  assert.equal(judgeNightAlert(room('night_action', { nightActions: [kill(1, 5), kill(3, 5)] })), '', '统一了不提示')
})

test('playersNeverGetJudgeMarks', () => {
  const snapshot = room('night_action', { judgeViewer: false, nightActions: [kill(1, 5)] })
  assert.equal(judgeSeatMarks(snapshot, 'night').size, 0)
  assert.equal(judgeNightAlert(snapshot), '')
  assert.equal(judgeVotePanel({ ...room('exile_vote'), judgeViewer: false }), null)
})

test('speechMarksShowTheWholeOrder', () => {
  const marks = judgeSeatMarks(room('day_speech', { speech: { order: [3, 4, 1, 6, 7], index: 3 } }), 'speech')
  assert.deepEqual([3, 4, 1].map((seat) => [marks.get(seat).tag, marks.get(seat).tagKind]), [['✓', 'done'], ['✓', 'done'], ['✓', 'done']])
  assert.deepEqual([marks.get(6).tag, marks.get(6).tagKind], ['4', 'moon'])
  assert.equal(marks.get(7).tag, '5')
  assert.equal(marks.get(8), undefined, '不在顺序里的没有标记')
})

test('voteMarksShowWhoVotedForWhomLive', () => {
  const snapshot = room('exile_vote', {
    voteTallies: [
      { targetSeatNo: 3, voteCount: 2, voterSeatNos: [2, 4] },
      { targetSeatNo: 1, voteCount: 2, voterSeatNos: [6, 7] },
    ],
  })
  const marks = judgeSeatMarks(snapshot, 'vote')
  assert.equal(marks.get(2).tag, '→3')
  assert.deepEqual([marks.get(3).count, marks.get(3).countKind], ['2.5', 'red'], '警长 1.5 票，最高票标红')
  assert.deepEqual([marks.get(1).count, marks.get(1).countKind], ['2', 'light'])
  assert.deepEqual([marks.get(8).tag, marks.get(8).tagKind], ['未投', 'gold'])
  const panel = judgeVotePanel(snapshot)
  assert.deepEqual(panel.rows.map((row) => [row.seatNo, row.count, row.pct, row.top]), [[3, '2.5 票', 100, true], [1, '2 票', 80, false]])
  assert.deepEqual(panel.waiting, [1, 3, 5, 8, 9, 12])
})

test('pkVotersExcludeThePkSeats', () => {
  const panel = judgeVotePanel(room('vote_pk', { pkSeats: [3, 8], voteTallies: [{ targetSeatNo: 3, voteCount: 1, voterSeatNos: [1] }] }))
  assert.deepEqual(panel.waiting, [2, 4, 5, 6, 7, 9, 12])
})

test('lastNightRecapForTheJudge', () => {
  const snapshot = room('day_speech', {
    rounds: [{ dayNo: 2, nightDeaths: [5], exile: [], skills: [] }],
    nightHistory: [kill(1, 5), kill(3, 5), { actorSeatNo: 8, targetSeatNo: 3, actionType: 'check', dayNo: 2 }],
  })
  const text = judgeRecap(snapshot)
  assert.match(text, /狼人 刀 5 号/)
  assert.match(text, /预言家 验 3 号（狼人）/)
  assert.equal(judgeRecap(room('day_speech', { rounds: [{ dayNo: 2, nightDeaths: [] }] })), '昨晚没人行动')
  assert.equal(judgeRecap({ ...snapshot, judgeViewer: false }), '')
})

test('tappingASeatOpensItsSheet', () => {
  const speaking = room('day_speech', { speech: { order: [9, 1], index: 0 } })
  const sheet = judgeSeatSheet(speaking, 9)
  assert.equal(sheet.title, '9 号 P9 · 白痴 · 存活')
  assert.equal(typeof sheet.content, 'string')
  assert.deepEqual(sheet.items.map((item) => [item.key, item.label]), [['skip', '跳过他的发言'], ['forceOut', '点杀 9 号']])
  assert.deepEqual(judgeSeatSheet(speaking, 1).items.map((item) => item.key), ['forceOut'], '不是他在说就没有跳过')
  assert.deepEqual(judgeSeatSheet(speaking, 10).items, [], '出局的人只看经历')
  assert.equal(judgeSeatSheet({ ...speaking, judgeViewer: false }, 9), null)
})
