import test from 'node:test'
import assert from 'node:assert/strict'
import { announcePanel, sheriffPanel } from '../src/utils/judge/sheriff.js'

const players = [1, 2, 3, 4, 5, 6].map((seatNo) => ({ seatNo, nickname: `P${seatNo}`, alive: true, playing: true }))
const room = (phase, self, extra = {}) => ({
  roomStatus: 'playing', currentPhase: phase, currentDay: 1, judgeViewer: false, players,
  selfPlayer: self, sheriffCandidates: [], pkSeats: [], dayInfo: null, speech: null, rounds: [], ...extra,
})
const me = (seatNo, alive = true) => ({ seatNo, alive, self: true })

test('signupHidesOthersChoices', () => {
  const out = sheriffPanel(room('sheriff_signup', me(2), { dayInfo: { sheriffCandidate: false } }))
  assert.equal(out.stage, 'signup')
  assert.equal(out.canSignUp, true)
  assert.equal(out.candidate, false)
  assert.deepEqual(out.targets, [], '报名阶段不列别人')
  assert.equal(sheriffPanel(room('sheriff_signup', me(2), { dayInfo: { sheriffCandidate: true } })).candidate, true)
  assert.equal(sheriffPanel(room('sheriff_signup', me(2, false))).canSignUp, false)
  assert.equal(sheriffPanel(room('sheriff_signup', { seatNo: null, judgeObserver: true }, { judgeViewer: true })).canSignUp, false)
})

test('speechStageLetsCandidatesWithdraw', () => {
  const out = sheriffPanel(room('sheriff_speech', me(3), { sheriffCandidates: [3, 5], speech: { order: [5, 3], index: 0 } }))
  assert.equal(out.stage, 'speech')
  assert.equal(out.canWithdraw, true)
  assert.equal(out.speakerSeat, 5)
  assert.equal(sheriffPanel(room('sheriff_speech', me(4), { sheriffCandidates: [3, 5] })).canWithdraw, false)
})

test('candidatesCannotVote', () => {
  const voter = sheriffPanel(room('sheriff_vote', me(1), { sheriffCandidates: [3, 5], dayInfo: { canVote: true, voted: false, votedCount: 1, voterCount: 4 } }))
  assert.equal(voter.canVote, true)
  assert.deepEqual(voter.targets, [3, 5])
  assert.equal(voter.progress, '已投 1 / 4')
  const candidate = sheriffPanel(room('sheriff_vote', me(3), { sheriffCandidates: [3, 5], dayInfo: { canVote: false, votedCount: 1, voterCount: 4 } }))
  assert.equal(candidate.canVote, false)
})

test('pkVotesOnlyForTiedSeats', () => {
  const out = sheriffPanel(room('sheriff_pk', me(1), { sheriffCandidates: [2, 3, 5], pkSeats: [3, 5], dayInfo: { canVote: true, voted: true, myVote: 3, votedCount: 2, voterCount: 3 } }))
  assert.equal(out.stage, 'pk')
  assert.deepEqual(out.targets, [3, 5])
  assert.equal(out.voted, true)
  assert.equal(out.myVote, 3)
})

test('onlyTheSheriffChoosesTheOrder', () => {
  const extra = { sheriffSeatNo: 2, latestAnnouncement: '昨晚 4 号出局', rounds: [{ dayNo: 1, nightDeaths: [4] }] }
  const sheriff = announcePanel(room('day_announce', me(2), extra))
  assert.deepEqual(sheriff.deaths, [4])
  assert.equal(sheriff.sheriffSeat, 2)
  assert.equal(sheriff.canChooseOrder, true)
  assert.deepEqual(sheriff.orderOptions.map((option) => option.value), ['left', 'right', 'dead_left', 'dead_right'])
  assert.equal(announcePanel(room('day_announce', me(3), extra)).canChooseOrder, false)
  const quiet = announcePanel(room('day_announce', me(2), { sheriffSeatNo: 2, rounds: [{ dayNo: 1, nightDeaths: [] }] }))
  assert.deepEqual(quiet.deaths, [])
  assert.equal(quiet.text, '昨晚是平安夜')
  assert.deepEqual(quiet.orderOptions.map((option) => option.label), ['警左', '警右'])
  assert.equal(announcePanel(room('day_announce', me(2, false), extra)).canChooseOrder, false)
})

test('sheriffVoteSaysWhyYouCannotVote', () => {
  assert.equal(sheriffPanel(room('sheriff_vote', { seatNo: null }, { judgeViewer: true, sheriffCandidates: [3] })).reason, '法官不投票')
  assert.equal(sheriffPanel(room('sheriff_vote', me(3), { sheriffCandidates: [3], dayInfo: { canVote: false } })).reason, '你在警上，不能投票')
})
