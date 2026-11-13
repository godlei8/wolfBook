import test from 'node:test'
import assert from 'node:assert/strict'
import { hostActions, speechPanel, voteResultPanel, votePanel } from '../src/utils/judge/day.js'

const players = [1, 2, 3, 4, 5, 6].map((seatNo) => ({ seatNo, nickname: `P${seatNo}`, alive: seatNo !== 6, playing: true }))
const room = (phase, self, extra = {}) => ({
  roomStatus: 'playing', currentPhase: phase, currentDay: 2, judgeViewer: false, host: false, judgeMode: 'observer', paused: false,
  players, selfPlayer: self, speech: null, dayInfo: null, pkSeats: [], rounds: [], voteTallies: [], ...extra,
})
const me = (seatNo, alive = true) => ({ seatNo, alive, self: true })

test('onlyTheSpeakerEndsTheirSpeech', () => {
  const speech = { order: [3, 4, 5, 1, 2], index: 1 }
  const speaker = speechPanel(room('day_speech', me(4), { speech }))
  assert.equal(speaker.speakerSeat, 4)
  assert.equal(speaker.isSpeaker, true)
  assert.equal(speaker.nextSeat, 5)
  assert.equal(speechPanel(room('day_speech', me(5), { speech })).isSpeaker, false)
  assert.equal(speechPanel(room('day_speech', me(4, false), { speech })).isSpeaker, false)
  assert.equal(speechPanel(room('day_speech', me(4))).speakerSeat, null)
})

test('voteProgressHidesTargets', () => {
  const panel = votePanel(room('exile_vote', me(1), { dayInfo: { canVote: true, voted: false, votedCount: 3, voterCount: 5 } }))
  assert.equal(panel.stage, 'exile')
  assert.equal(panel.canVote, true)
  assert.equal(panel.abstainAllowed, true)
  assert.equal(panel.progress, '已投 3 / 5')
  assert.deepEqual(panel.targets, [1, 2, 3, 4, 5])
  assert.equal(JSON.stringify(panel).includes('voterSeatNos'), false)
  const pk = votePanel(room('vote_pk', me(1), { pkSeats: [2, 4], dayInfo: { canVote: true, voted: true, myVote: null, votedCount: 1, voterCount: 3 } }))
  assert.equal(pk.stage, 'pk')
  assert.deepEqual(pk.targets, [2, 4])
  assert.equal(pk.mine, '你弃票了')
  assert.equal(votePanel(room('vote_pk', me(2), { pkSeats: [2, 4], dayInfo: { canVote: false } })).canVote, false, '平票者不能投')
})

test('revealedIdiotCannotVote', () => {
  assert.equal(votePanel(room('exile_vote', me(3), { dayInfo: { canVote: false, votedCount: 0, voterCount: 4 } })).canVote, false)
  assert.equal(votePanel(room('exile_vote', me(3))).canVote, false)
})

test('voteResultShowsTheTallyOfTheLastRound', () => {
  const rounds = [{
    dayNo: 2,
    nightDeaths: [],
    exile: [{ tallies: [{ targetSeatNo: 3, weight: 2.5, voterSeatNos: [1, 2] }, { targetSeatNo: 4, weight: 1, voterSeatNos: [5] }], abstainSeatNos: [], eliminatedSeatNo: 3, pkSeatNos: [] }],
    skills: [{ kind: 'idiot_revealed', actorSeatNo: 3, targetSeatNo: null }],
  }]
  const panel = voteResultPanel(room('vote_result', me(1), { rounds }))
  assert.deepEqual(panel.tallies.map((tally) => [tally.targetSeatNo, tally.weight, tally.voters]), [[3, 2.5, '1、2'], [4, 1, '5']])
  assert.equal(panel.eliminatedSeat, 3)
  assert.equal(panel.revealedIdiot, 3)
  const tie = voteResultPanel(room('vote_result', me(1), { pkSeats: [2, 4], rounds: [{ dayNo: 2, exile: [{ tallies: [], abstainSeatNos: [1, 3], eliminatedSeatNo: null, pkSeatNos: [2, 4] }], skills: [] }] }))
  assert.deepEqual(tie.pkSeats, [2, 4])
  assert.equal(tie.abstain, '1、3')
  assert.equal(tie.eliminatedSeat, null)
})

test('onlyTheJudgeGetsAdvanceButtons', () => {
  // 用户给定：白天黑夜由系统推进；有法官时法官可以不等倒计时提前推进，无法官房主没有推进按钮
  const host = (phase, extra = {}) => hostActions(room(phase, { seatNo: null, judgeObserver: true }, { host: true, judgeViewer: true, ...extra })).map((item) => item.action)
  assert.deepEqual(host('sheriff_signup'), ['close_sheriff_signup'])
  assert.deepEqual(host('sheriff_speech'), ['next_speaker', 'start_sheriff_vote'])
  assert.deepEqual(host('sheriff_vote'), ['resolve_sheriff_vote'])
  assert.deepEqual(host('sheriff_pk'), ['resolve_sheriff_vote'])
  assert.deepEqual(host('day_announce'), ['start_speech'])
  assert.deepEqual(host('day_speech'), ['next_speaker'], '发完言自动进投票，没有「发起投票」')
  assert.deepEqual(host('exile_vote'), ['resolve_vote'])
  assert.deepEqual(host('vote_pk'), ['resolve_vote'])
  assert.deepEqual(host('vote_result'), ['continue'])
  assert.deepEqual(host('night_action'), [])
  assert.deepEqual(hostActions(room('day_speech', me(1), { host: true, judgeMode: 'joined' })), [], '无法官房主没有推进按钮')
  assert.deepEqual(hostActions(room('day_speech', me(1))), [], '玩家没有按钮')
  assert.ok(hostActions(room('day_speech', null, { host: true, judgeViewer: true, paused: true })).every((item) => item.disabled), '暂停中都不能点')
})

test('hostActionsNeverCarryResultFields', () => {
  const phases = ['sheriff_signup', 'sheriff_speech', 'sheriff_vote', 'day_announce', 'day_speech', 'exile_vote', 'vote_pk', 'vote_result']
  for (const phase of phases) {
    for (const item of hostActions(room(phase, null, { host: true, judgeViewer: true }))) {
      assert.deepEqual(Object.keys(item.payload), ['action'], `${phase} ${item.action}`)
    }
  }
})

test('whyYouCannotVote', () => {
  assert.equal(votePanel(room('exile_vote', { seatNo: null, judgeObserver: true }, { judgeViewer: true })).reason, '法官不投票')
  assert.equal(votePanel(room('exile_vote', me(3), { dayInfo: { canVote: false } })).reason, '你这一轮不能投票')
  assert.equal(votePanel(room('exile_vote', me(3, false))).reason, '你已出局，不能投票')
})

test('speechClockWarnsAtThirtyOnlyForLongSpeeches', () => {
  assert.equal(speechPanel(room('day_speech', me(1), { rules: { speechSeconds: 120 } })).warnAt, 30)
  assert.equal(speechPanel(room('day_speech', me(1), { rules: { speechSeconds: 30 } })).warnAt, 10)
})

test('explodedDayAnnounceOnlyOffersNight', () => {
  const host = (extra = {}) => hostActions(room('day_announce', null, { host: true, judgeViewer: true, ...extra })).map((item) => item.action)
  assert.deepEqual(host({ nightNext: true }), ['start_night'])
  assert.deepEqual(host({ nightNext: false }), ['start_speech'])
})

test('pkSpeechSaysPk', () => {
  const panel = speechPanel(room('day_speech', me(4), { speech: { order: [8, 3], index: 0, direction: null, pk: true } }))
  assert.equal(panel.directionText, 'PK 发言')
})

test('speechOnlyTellsTheDirection', () => {
  // 用户给定：游戏中不列整个发言顺序，只提示警左 / 警右（死左 / 死右，随机时说从几号开始）
  const panel = (direction, order = [3, 4, 5]) => speechPanel(room('day_speech', me(4), { speech: { order, index: 0, direction } }))
  assert.equal(panel('left').directionText, '警左')
  assert.equal(panel('right').directionText, '警右')
  assert.equal(panel('dead_left').directionText, '死左')
  assert.equal(panel('dead_right').directionText, '死右')
  assert.equal(panel(null).directionText, '从 3 号开始')
  assert.equal(panel(null, []).directionText, '')
  assert.equal('sequence' in panel('left'), false)
})
