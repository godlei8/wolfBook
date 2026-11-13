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
  assert.equal(announcePanel(room('day_announce', me(2), { ...extra, nightNext: true })).canChooseOrder, false, '今天直接天黑')
})

test('announceShowsTheChosenSpeechOrder', () => {
  // 实测问题 3：警长选完，别人看到「警长选了警右」；用户给定：限时内没选系统随机
  const round = { dayNo: 1, nightDeaths: [] }
  const deadline = '2026-11-13T20:00:15'
  const now = Date.parse('2026-11-13T20:00:03+08:00')
  const other = announcePanel(room('day_announce', me(4), { sheriffSeatNo: 2, rounds: [round], sheriffOrder: 'right' }), now)
  assert.equal(other.chosenOrderText, '警长选了警右')
  const waiting = announcePanel(room('day_announce', me(4), { sheriffSeatNo: 2, rounds: [round], sheriffOrder: null, phaseDeadline: deadline }), now)
  assert.equal(waiting.chosenOrderText, '等警长选择，没选系统随机警左或警右')
  const deciding = announcePanel(room('day_announce', me(2), { sheriffSeatNo: 2, rounds: [round], sheriffOrder: null, phaseDeadline: deadline }), now)
  assert.equal(deciding.chosenOrderText, '12 秒内选好，没选系统随机警左或警右')
  const sheriff = announcePanel(room('day_announce', me(2), { sheriffSeatNo: 2, rounds: [round], sheriffOrder: 'left' }))
  assert.equal(sheriff.chosenOrder, 'left')
  assert.equal(sheriff.chosenOrderText, '你选了警左，开始发言前还能改')
})

test('sheriffVoteSaysWhyYouCannotVote', () => {
  assert.equal(sheriffPanel(room('sheriff_vote', { seatNo: null }, { judgeViewer: true, sheriffCandidates: [3] })).reason, '法官不投票')
  assert.equal(sheriffPanel(room('sheriff_vote', me(3), { sheriffCandidates: [3], dayInfo: { canVote: false } })).reason, '你在警上，不能投票')
})

test('theSheriffSpeakerCanEndTheirSpeech', () => {
  const speaking = room('sheriff_speech', me(5), { sheriffCandidates: [3, 5], speech: { order: [5, 3], index: 0 } })
  assert.equal(sheriffPanel(speaking).isSpeaker, true)
  assert.equal(sheriffPanel({ ...speaking, selfPlayer: me(3) }).isSpeaker, false, '还没轮到')
  assert.equal(sheriffPanel({ ...speaking, currentPhase: 'sheriff_vote' }).isSpeaker, false, '投票阶段没有发言人')
})

test('theAnnouncementCountsDownToSpeechOrNight', () => {
  // 用户给定：公布死讯后停 5 秒自动开始发言；警上自爆那天自动天黑
  const deadline = '2026-11-13T20:00:05'
  const now = Date.parse('2026-11-13T20:00:02+08:00')
  assert.equal(announcePanel(room('day_announce', me(2), { phaseDeadline: deadline }), now).hint, '3 秒后自动开始发言')
  assert.equal(announcePanel(room('day_announce', me(2), { phaseDeadline: deadline, nightNext: true }), now).hint, '3 秒后自动天黑')
  assert.equal(announcePanel(room('day_announce', me(2), { sheriffSeatNo: 2, phaseDeadline: deadline }), now).hint, '3 秒后自动开始发言，警长最后发言')
  assert.equal(announcePanel(room('day_announce', me(2), { phaseDeadline: null }), now).hint, '马上开始发言')
})

test('otherCandidatesVoteInTheSheriffPk', () => {
  const pk = (seat, canVote) => sheriffPanel(room('sheriff_pk', me(seat), { sheriffCandidates: [2, 3, 5], pkSeats: [3, 5], dayInfo: { canVote, voted: false, votedCount: 0, voterCount: 5 } }))
  assert.equal(pk(2, true).canVote, true, '还在警上、不在 PK 的人 PK 时能投')
  assert.equal(pk(3, false).canVote, false)
  assert.equal(pk(3, false).reason, '你在 PK，不能投票')
  assert.equal(sheriffPanel(room('sheriff_vote', me(2), { sheriffCandidates: [2, 3, 5], dayInfo: { canVote: false } })).canVote, false, '第一轮警上的人不能投')
})

test('signupShowsWhatIChose', () => {
  // 实测问题 2：没选、选了不上警、上警三种要分得开
  const undecided = sheriffPanel(room('sheriff_signup', me(2), { dayInfo: { sheriffCandidate: null } }))
  assert.equal(undecided.choice, null)
  assert.equal(undecided.choiceText, '报名只有你自己看得到，到点自动截止。')
  const declined = sheriffPanel(room('sheriff_signup', me(2), { dayInfo: { sheriffCandidate: false } }))
  assert.equal(declined.choice, 'down')
  assert.equal(declined.choiceText, '你选了不上警，截止前还能改')
  const up = sheriffPanel(room('sheriff_signup', me(2), { dayInfo: { sheriffCandidate: true } }))
  assert.equal(up.choice, 'up')
  assert.equal(up.choiceText, '你报名上警了，截止前还能改')
})
