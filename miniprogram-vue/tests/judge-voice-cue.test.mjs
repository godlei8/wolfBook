import test from 'node:test'
import assert from 'node:assert/strict'
import { rememberCue, shouldPlay, voiceCue, withTimeout } from '../src/utils/judge/voice-cue.js'
import { parseServerTime } from '../src/utils/judge/room-view.js'

const NOW = parseServerTime('2026-10-30T20:00:00')
const night = (index, kind, roleName, extra = {}) => ({
  roomId: 'r1', roomStatus: 'playing', currentPhase: 'night_action', currentDay: 1, host: true, paused: false,
  currentStep: { index, total: 4, kind, roleName, line: '', deadline: '2026-10-30T20:00:40' },
  phaseDeadline: '2026-10-30T20:00:40', rounds: [], ...extra,
})

test('onlyTheHostSpeaks', () => {
  assert.equal(voiceCue({ ...night(0, 'wolf', '狼人'), host: false }, NOW), null)
  assert.ok(voiceCue(night(0, 'wolf', '狼人'), NOW))
  assert.equal(voiceCue({ ...night(0, 'wolf', '狼人'), paused: true }, NOW), null, '暂停不播')
})

test('voiceCueIsPlayedOncePerStep', () => {
  const first = voiceCue(night(0, 'wolf', '狼人'), NOW)
  const again = voiceCue(night(0, 'wolf', '狼人'), NOW + 1500)
  assert.equal(first.key, again.key)
  const next = voiceCue(night(1, 'witch', '女巫'), NOW)
  assert.notEqual(next.key, first.key)
  const played = rememberCue([], first)
  assert.equal(shouldPlay(again, played), false)
  assert.equal(shouldPlay(next, played), true)
  assert.equal(shouldPlay(null, played), false)
  const many = Array.from({ length: 40 }, (_, i) => ({ key: `k${i}` })).reduce(rememberCue, [])
  assert.equal(many.length, 30, '只记最近 30 条')
  const otherRoom = voiceCue({ ...night(0, 'wolf', '狼人'), roomId: 'r2' }, NOW)
  assert.notEqual(otherRoom.key, first.key)
})

test('nightLinesDoNotNeedTheStepLine', () => {
  const first = voiceCue(night(0, 'guard', '守卫'), NOW)
  assert.deepEqual(first.lines, ['天黑请闭眼', '守卫请睁眼，请选择今晚要守护的人'])
  const witch = voiceCue(night(1, 'witch', '女巫'), NOW)
  assert.equal(witch.lines.length, 1)
  assert.ok(witch.lines[0].startsWith('女巫请睁眼'))
})

test('tenSecondWarning', () => {
  const late = voiceCue(night(1, 'witch', '女巫', { phaseDeadline: '2026-10-30T20:00:08' }), NOW)
  assert.deepEqual(late.lines, ['请尽快行动'])
  assert.notEqual(late.key, voiceCue(night(1, 'witch', '女巫'), NOW).key)
  const over = voiceCue(night(1, 'witch', '女巫', { phaseDeadline: '2026-10-30T19:59:58' }), NOW)
  assert.ok(over.lines[0].startsWith('女巫请睁眼'), '到点了就不再提醒')
})

test('thirtySecondSpeechWarning', () => {
  const speech = (deadline) => ({
    roomId: 'r1', roomStatus: 'playing', currentPhase: 'day_speech', currentDay: 2, host: true, paused: false,
    speech: { order: [3, 4], index: 0 }, phaseDeadline: deadline, rounds: [],
  })
  assert.deepEqual(voiceCue(speech('2026-10-30T20:01:30'), NOW).lines, ['请 3 号发言'])
  const warn = voiceCue(speech('2026-10-30T20:00:25'), NOW)
  assert.deepEqual(warn.lines, ['发言还剩 30 秒'])
  assert.notEqual(warn.key, voiceCue(speech('2026-10-30T20:01:30'), NOW).key)
})

test('dayCues', () => {
  const day = (phase, extra = {}) => voiceCue({ roomId: 'r1', roomStatus: 'playing', currentPhase: phase, currentDay: 2, host: true, paused: false, rounds: [], ...extra }, NOW)
  assert.deepEqual(day('day_announce', { rounds: [{ dayNo: 2, nightDeaths: [5] }] }).lines, ['天亮了', '昨晚 5 号出局'])
  assert.deepEqual(day('day_announce', { rounds: [{ dayNo: 2, nightDeaths: [] }] }).lines, ['天亮了', '昨晚是平安夜'])
  assert.deepEqual(day('exile_vote').lines, ['发言结束，请投票'])
  assert.deepEqual(day('sheriff_signup', { currentDay: 1 }).lines, ['天亮了', '现在开始上警，想竞选警长的请在手机上报名'])
  assert.deepEqual(day('day_announce', { currentDay: 1, sheriffSeatNo: 4, rounds: [{ dayNo: 1, nightDeaths: [], sheriff: { electedSeatNo: 4 } }] }).lines, ['4 号当选警长', '昨晚是平安夜'])
  assert.deepEqual(voiceCue({ roomId: 'r1', roomStatus: 'finished', host: true, winVerdict: { camp: '好人', reason: '狼人阵营全部出局，好人阵营胜利' }, winnerCamp: '好人' }, NOW).lines, ['游戏结束', '狼人阵营全部出局，好人阵营胜利'])
})

test('secondVoteResultAndSecondPendingOfTheDayAreStillSpoken', () => {
  const base = { roomId: 'r1', roomStatus: 'playing', currentDay: 2, host: true, paused: false, pkSeats: [] }
  const tie = { dayNo: 2, nightDeaths: [], exile: [{ tallies: [], abstainSeatNos: [], eliminatedSeatNo: null, pkSeatNos: [2, 4] }], skills: [] }
  const out = { ...tie, exile: [...tie.exile, { tallies: [], abstainSeatNos: [], eliminatedSeatNo: 4, pkSeatNos: [] }] }
  const first = voiceCue({ ...base, currentPhase: 'vote_result', rounds: [tie] }, NOW)
  const second = voiceCue({ ...base, currentPhase: 'vote_result', rounds: [out] }, NOW)
  assert.notEqual(first.key, second.key)
  assert.deepEqual(second.lines, ['4 号被放逐'])
  const pending = (deadline) => voiceCue({ ...base, currentPhase: 'skill_pending', pending: null, phaseDeadline: deadline, rounds: [] }, NOW)
  assert.notEqual(pending('2026-10-30T20:00:10').key, pending('2026-10-30T20:03:10').key, '房主不是当事人时，两次待办也要各播一次')
})

test('shortSpeechesStillNameTheSpeaker', () => {
  const speech = { roomId: 'r1', roomStatus: 'playing', currentPhase: 'day_speech', currentDay: 2, host: true, paused: false,
    speech: { order: [3, 4], index: 0 }, phaseDeadline: '2026-10-30T20:00:29', rounds: [], rules: { speechSeconds: 30 } }
  assert.deepEqual(voiceCue(speech, NOW).lines, ['请 3 号发言'], '发言时长只有 30 秒时不提醒还剩 30 秒')
})

test('stuckPlaybackIsGivenUp', async () => {
  const never = new Promise(() => {})
  const started = Date.now()
  await assert.rejects(withTimeout(never, 30), /timeout/)
  assert.ok(Date.now() - started < 1000)
  assert.equal(await withTimeout(Promise.resolve(7), 30), 7)
})
