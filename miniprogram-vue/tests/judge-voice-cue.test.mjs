import test from 'node:test'
import assert from 'node:assert/strict'
import { rememberCue, retryCue, shouldPlay, voiceCue, withTimeout } from '../src/utils/judge/voice-cue.js'
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
  const words = voiceCue({ ...base, currentPhase: 'skill_pending', phaseDeadline: '2026-10-30T20:02:00', rounds: [],
    currentPending: { kind: 'last_words', seatNo: 6, deadline: '2026-10-30T20:02:00' } }, NOW)
  assert.deepEqual(words.lines, ['请 6 号留遗言'])
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

test('aFailedCueIsRetriedOnce', () => {
  const cue = { key: 'night:1:0', lines: ['天黑请闭眼'] }
  let played = rememberCue([], cue)
  let failures = {}
  ;({ playedKeys: played, failures } = retryCue(played, cue, failures))
  assert.equal(shouldPlay(cue, played), true, '第一次失败后放开，下一秒重播')
  played = rememberCue(played, cue)
  ;({ playedKeys: played, failures } = retryCue(played, cue, failures))
  assert.equal(shouldPlay(cue, played), false, '重播也失败就不再播，免得每秒刷一次')
  assert.equal(failures[cue.key], 2)
})

test('explodedAnnounceEndsWithNightfall', () => {
  const day = (extra = {}) => ({
    roomId: 'r1', roomStatus: 'playing', currentPhase: 'day_announce', currentDay: 1, host: true, paused: false,
    latestAnnouncement: '4号自爆，警长竞选推迟到明天。昨夜平安夜', rounds: [], players: [], ...extra,
  })
  const exploded = voiceCue(day({ nightNext: true }), NOW)
  assert.ok(!exploded.lines.includes('天黑请闭眼'), '遗言还没说完，不能先喊天黑')
  assert.match(exploded.lines.at(-1), /遗言/)
  assert.ok(exploded.lines.includes('4号自爆，警长竞选推迟到明天。昨夜平安夜'))
  assert.notEqual(exploded.key, voiceCue(day(), NOW).key)
})

test('deferredElectionSaysItContinues', () => {
  const speech = voiceCue({
    roomId: 'r1', roomStatus: 'playing', currentPhase: 'sheriff_speech', currentDay: 2, host: true, paused: false,
    speech: { order: [3, 6], index: 0 }, rounds: [], players: [],
  }, NOW)
  assert.deepEqual(speech.lines.slice(0, 2), ['天亮了', '昨天的警长竞选继续'], '推迟到今天的竞选，这一天也要先喊天亮了')
})

test('daySpeechExplosionIsAnnouncedAtNightfall', () => {
  const first = voiceCue(night(0, 'wolf', '狼人', { currentDay: 2, latestAnnouncement: '4号自爆，天黑请闭眼' }), NOW)
  assert.equal(first.lines[0], '4号自爆，天黑请闭眼')
  assert.equal(first.lines.filter((line) => line.includes('天黑请闭眼')).length, 1)
  assert.equal(voiceCue(night(0, 'wolf', '狼人', { latestAnnouncement: '天黑请闭眼' }), NOW).lines[0], '天黑请闭眼')
})

test('newNightStepsHaveTheirOwnLines', () => {
  assert.ok(voiceCue(night(0, 'mech_wolf', '机械狼'), NOW).lines.some((line) => line.includes('请决定今晚的行动')))
  assert.ok(voiceCue(night(4, 'psychic', '通灵师'), NOW).lines.some((line) => line.includes('请选择要查验的人')))
  assert.ok(voiceCue(night(5, 'idle', '白痴'), NOW).lines.some((line) => line.includes('请稍候')))
})

test('dawnIsAnnouncedBeforeTheLastWords', () => {
  // 昨夜有人出局：房间一天亮就直接进了「留遗言」。房主手机要先喊「天亮了，昨晚 X 号出局」，再喊「请 X 号留遗言」
  const pending = (extra = {}) => ({
    roomId: 'r1', roomStatus: 'playing', currentPhase: 'skill_pending', currentDay: 2, host: true, paused: false,
    rounds: [{ dayNo: 2, nightDeaths: [5], exile: [] }], players: [],
    currentPending: { kind: 'last_words', seatNo: 5 }, phaseDeadline: '2026-10-30T20:02:00', ...extra,
  })
  const dawn = voiceCue(pending(), NOW, [])
  assert.deepEqual(dawn.lines, ['天亮了', '昨晚 5 号出局'])
  const announce = voiceCue(pending({ currentPhase: 'day_announce', currentPending: null }), NOW, [])
  assert.equal(dawn.key, announce.key, '和公布死讯是同一句，说过就不再说')

  const after = voiceCue(pending(), NOW, [dawn.key])
  assert.deepEqual(after.lines, ['请 5 号留遗言'], '喊完天亮了，下一秒接着喊留遗言')

  // 第一天有上警：竞选完才公布死讯，同样先说结果和死讯，再留遗言
  const elected = voiceCue(pending({ currentDay: 1, sheriffSeatNo: 4, rounds: [{ dayNo: 1, nightDeaths: [5], exile: [], sheriff: { electedSeatNo: 4 } }] }), NOW, [])
  assert.deepEqual(elected.lines, ['4 号当选警长', '昨晚 5 号出局'])
})

test('laterPendingsOfTheDayDoNotAnnounceDawnAgain', () => {
  // 白天被放逐后的遗言、被带走的人的待办：不是天亮那一次，不再喊天亮了
  const exiled = {
    roomId: 'r1', roomStatus: 'playing', currentPhase: 'skill_pending', currentDay: 2, host: true, paused: false,
    rounds: [{ dayNo: 2, nightDeaths: [], exile: [{ eliminatedSeatNo: 7 }] }], players: [],
    currentPending: { kind: 'last_words', seatNo: 7 }, phaseDeadline: '2026-10-30T20:09:00',
  }
  assert.deepEqual(voiceCue(exiled, NOW, []).lines, ['请 7 号留遗言'])
  // 警上自爆那天：公告由「X号自爆…」那一句来说，这里不抢
  const boom = { ...exiled, rounds: [{ dayNo: 2, nightDeaths: [5], exile: [] }], nightNext: true, currentPending: { kind: 'last_words', seatNo: 4 } }
  assert.deepEqual(voiceCue(boom, NOW, []).lines, ['请 4 号留遗言'])
  // 不传播过的记录（老的调用方式）时和原来一样，不抢着说
  assert.deepEqual(voiceCue({ ...exiled, rounds: [{ dayNo: 2, nightDeaths: [5], exile: [] }], currentPending: { kind: 'last_words', seatNo: 5 } }, NOW).lines, ['请 5 号留遗言'])
})

test('nightfallIsAlwaysAnnounced', () => {
  // 入夜第一句永远是「天黑请闭眼」，不管这个板子夜里第一个睁眼的是谁
  for (const [kind, role] of [['wolf', '狼人'], ['guard', '守卫'], ['mech_wolf', '机械狼'], ['idle', '白痴']]) {
    assert.equal(voiceCue(night(0, kind, role, { currentDay: 3 }), NOW).lines[0], '天黑请闭眼')
  }
  assert.ok(!voiceCue(night(1, 'witch', '女巫'), NOW).lines.includes('天黑请闭眼'), '只在入夜那一刻说一次')
})
