import test from 'node:test'
import assert from 'node:assert/strict'
import { createNote, dayOf, seatOf } from '../src/utils/notes/model.js'
import { setGuess, toggleOut, toggleSheriff } from '../src/utils/notes/board.js'
import { electionSection, electionView, entryView, finishEntry, pickTarget, restAbstain, startEntry, toggleVoter, voteView } from '../src/utils/notes/votes.js'

const fresh = (extra = {}) => createNote({
  sessionId: 's1', boardMode: 'custom', boardName: '周五面杀局', playerCount: 12, wolves: 4, now: '2026-11-16T12:00:00.000Z', ...extra,
})

// 第 1 天：7 号首夜出局，3 号警长；1 3 6 8 10 11 投 5，4 5 9 投 3，2 12 投 9
function dayOne() {
  let note = toggleSheriff(toggleOut(fresh(), 7, 'night', 1), 3)
  for (const no of [4, 5, 9]) note = setGuess(note, no, 'wolf')
  note = setGuess(note, 3, 'god')
  let entry = startEntry(note, 1)
  entry = pickTarget(entry, 5)
  for (const no of [1, 3, 6, 8, 10, 11]) entry = toggleVoter(entry, no)
  entry = pickTarget(entry, 3)
  for (const no of [4, 5, 9]) entry = toggleVoter(entry, no)
  entry = pickTarget(entry, 9)
  for (const no of [2, 12]) entry = toggleVoter(entry, no)
  return { note, entry }
}

test('twoStepEntryRecordsWhoVotedForWhom', () => {
  // 先点被投的人，再点投他的人；换下一个被投的人时上一组自动记下
  const { note, entry } = dayOne()
  const view = entryView(note, entry)
  assert.deepEqual(view.targets.map((item) => item.no), [1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12], '首夜出局的 7 号不在')
  assert.deepEqual(view.targets.filter((item) => item.on).map((item) => item.no), [9])
  assert.equal(view.step2, '再点投给 9 号的人')
  assert.deepEqual(view.voters.filter((item) => item.on).map((item) => item.no), [2, 12])
  assert.equal(view.voters.find((item) => item.no === 4).label, '投 3')
  assert.equal(view.voters.find((item) => item.no === 4).used, true, '已经记在别的组里，不能再点')
  assert.equal(view.voters.find((item) => item.no === 3).label, '投 5')
  assert.deepEqual(view.lines.map((line) => [line.title, line.votersText, line.countText]), [
    ['投 5 号', '1、3、6、8、10、11 号', '6.5 票'],
    ['投 3 号', '4、5、9 号', '3 票'],
    ['投 9 号', '2、12 号', '2 票'],
  ])
  assert.equal(view.remainingText, '')
  assert.equal(toggleVoter(entry, 4), entry, '点已经用过的人没反应')
})

test('finishingWritesTheVotesAndExilesTheTop', () => {
  const { note, entry } = dayOne()
  const done = finishEntry(note, entry)
  assert.deepEqual(dayOf(done, 1).votes, [
    { target: 5, voters: [1, 3, 6, 8, 10, 11], weight: 6.5 },
    { target: 3, voters: [4, 5, 9], weight: 3 },
    { target: 9, voters: [2, 12], weight: 2 },
  ], '票数连同当时的权重一起记下')
  assert.equal(dayOf(done, 1).exiled, 5)
  assert.deepEqual([seatOf(done, 5).out, seatOf(done, 5).outDay], ['exile', 1], '最高票那个人记成被放逐')
  assert.equal(dayOf(note, 1).votes.length, 0, '原来那份笔记没被改')
  const again = startEntry(done, 1)
  assert.deepEqual(again.groups.map((group) => group.target), [5, 3, 9], '记过的那天再点进来接着改')
  assert.deepEqual(dayOf(finishEntry(done, again), 1).votes, dayOf(done, 1).votes)
})

test('aTieExilesNobody', () => {
  const note = fresh({ playerCount: 6 })
  let entry = pickTarget(startEntry(note, 1), 1)
  entry = toggleVoter(toggleVoter(entry, 2), 3)
  entry = pickTarget(entry, 4)
  entry = toggleVoter(toggleVoter(entry, 5), 6)
  const done = finishEntry(note, entry)
  assert.equal(dayOf(done, 1).exiled, 0)
  assert.equal(seatOf(done, 1).out, '')
})

test('theRestCanBeMarkedAsAbstaining', () => {
  const note = fresh({ playerCount: 6 })
  let entry = pickTarget(startEntry(note, 1), 1)
  entry = toggleVoter(toggleVoter(entry, 2), 3)
  assert.equal(entryView(note, entry).remainingText, '还没记：1、4、5、6 号')
  entry = restAbstain(note, entry)
  assert.equal(entryView(note, entry).remainingText, '弃票：1、4、5、6 号')
  assert.deepEqual(dayOf(finishEntry(note, entry), 1).abstain, [1, 4, 5, 6])
})

test('pickingATargetAgainReopensItsGroup', () => {
  const note = fresh({ playerCount: 6 })
  let entry = pickTarget(startEntry(note, 1), 1)
  entry = toggleVoter(entry, 2)
  entry = pickTarget(entry, 4)
  entry = toggleVoter(entry, 5)
  entry = pickTarget(entry, 1)
  assert.deepEqual(entryView(note, entry).voters.filter((item) => item.on).map((item) => item.no), [2], '回到 1 号这一组接着改')
  entry = toggleVoter(entry, 2)
  assert.deepEqual(dayOf(finishEntry(note, entry), 1).votes, [{ target: 4, voters: [5], weight: 1 }], '改空的组不留')
})

test('voteViewGroupsByTargetAndColoursByMyMarks', () => {
  // 票型：按投给谁分组，方块颜色就是座位上标的颜色；一组里都是我标的狼，提示一句
  const { note, entry } = dayOne()
  const view = voteView(finishEntry(note, entry), 1)
  assert.equal(view.recorded, true)
  assert.equal(view.title, '第 1 天 · 5 号被放逐')
  assert.deepEqual(view.groups.map((group) => [group.title, group.countText]), [
    ['投 5 号', '6.5 票 · 含警长'], ['投 3 号', '3 票'], ['投 9 号', '2 票'],
  ])
  assert.deepEqual(view.groups[1].voters, [{ no: 4, tone: 'wolf', avatar: '' }, { no: 5, tone: 'wolf', avatar: '' }, { no: 9, tone: 'wolf', avatar: '' }])
  assert.equal(view.groups[1].hint, '这 3 张票都是你标成狼的人')
  assert.equal(view.groups[0].hint, '')
  assert.equal(view.groups[0].voters.find((voter) => voter.no === 3).tone, 'god')

  const empty = voteView(note, 2)
  assert.deepEqual([empty.recorded, empty.title, empty.groups], [false, '第 2 天 · 还没记', []])
  const mixed = voteView({ ...note, dayData: { 1: { votes: [{ target: 1, voters: [4, 9, 2] }] } } }, 1)
  assert.equal(mixed.groups[0].hint, '其中 2 张是你标成狼的人')
  assert.equal(mixed.title, '第 1 天 · 没有人被放逐')
})

test('theSheriffElectionIsOneBlockForTheWholeGame', () => {
  // 警长竞选单独一块放在票型页最上面，不跟着第几天；没记的时候面杀记到第 1 天
  const note = { ...fresh(), days: 3 }
  assert.deepEqual(electionSection(note), { items: [], addDay: 1 })
  const elected = { ...note, dayData: { 1: { election: { up: [], off: [], votes: [{ target: 2, voters: [6, 7] }], elected: 2, lost: false } } } }
  const section = electionSection(elected)
  assert.deepEqual(section.items.map((item) => [item.day, item.title]), [[1, '警长竞选 · 2 号当选']])
  assert.equal(section.addDay, 0, '选出警长了就不用再记')
  const lost = { ...note, dayData: { 1: { election: { up: [3], off: [], votes: [], elected: 0, lost: true } } } }
  assert.equal(electionSection(lost).addDay, 0, '警徽流失了也不用再记')
  // 竞选推迟（第 1 天警上有人自爆）：第 1 天那次没出结果，可以补记第 2 天的；两次都记了就标上第几天
  const postponed = { ...note, dayData: { 1: { election: { up: [3, 5], off: [], votes: [], elected: 0, lost: false } } } }
  assert.equal(electionSection(postponed).addDay, 2)
  assert.equal(electionSection({ ...postponed, days: 1 }).addDay, 0, '还没到第 2 天不出入口')
  const both = { ...postponed, dayData: { ...postponed.dayData, 2: { election: { up: [3, 5], off: [], votes: [{ target: 5, voters: [1, 2] }], elected: 5, lost: false } } } }
  assert.deepEqual(electionSection(both), {
    items: electionSection(both).items,
    addDay: 0,
  })
  assert.deepEqual(electionSection(both).items.map((item) => item.title), ['警长竞选 · 第 1 天 · 推迟到第 2 天', '警长竞选 · 第 2 天 · 5 号当选'])
})

test('theSheriffElectionShowsAboveTheExileVote', () => {
  // 票型页：那一天有警长竞选就先显示它——谁上警、谁退水、每个人得了谁的票、谁当选
  let note = setGuess(setGuess(fresh(), 4, 'wolf'), 6, 'wolf')
  assert.equal(electionView(note, 1), null, '没记过就没有这一块')
  note = { ...note, dayData: { 1: { election: { up: [3, 5, 9], off: [9], votes: [{ target: 5, voters: [4, 6] }, { target: 3, voters: [1, 2, 8, 10] }], elected: 3, lost: false } } } }
  const view = electionView(note, 1)
  assert.equal(view.title, '警长竞选 · 3 号当选')
  assert.equal(view.upText, '上警：3、5、9 号 · 退水：9 号')
  assert.deepEqual(view.groups.map((group) => [group.title, group.countText, group.voters.map((voter) => voter.no)]), [
    ['投 3 号', '4 票', [1, 2, 8, 10]], ['投 5 号', '2 票', [4, 6]],
  ], '票多的排前面；竞选时还没有警长，一人一票')
  assert.deepEqual(view.groups[1].voters.map((voter) => voter.tone), ['wolf', 'wolf'], '方块颜色还是我标的')
  assert.equal(view.groups[1].hint, '这 2 张票都是你标成狼的人')
  assert.deepEqual(electionSection(note).items, [{ ...view, day: 1 }], '整局一块，放在票型页最上面')

  const withElection = (election) => ({ ...note, dayData: { 1: { election } } })
  assert.equal(electionView(withElection({ up: [3, 5], off: [], votes: [], elected: 0, lost: false }), 1).title, '警长竞选 · 还没出结果')
  assert.equal(electionView(withElection({ up: [3, 5], off: [], votes: [], elected: 0, lost: true }), 1).title, '警长竞选 · 警徽流失')
  assert.equal(electionView(withElection({ up: [], off: [], votes: [{ target: 3, voters: [1] }, { target: 5, voters: [2] }], elected: 0, lost: false }), 1).title, '警长竞选 · 没有选出警长')
})

test('inFaceToFaceGamesTheSheriffVoteIsEnteredTheSameWay', () => {
  // 面杀：和记放逐票同一个两步流程。最高票只有一个时记他当选警长
  const note = toggleSheriff(fresh(), 8)
  let entry = startEntry(note, 1, 'sheriff')
  assert.equal(entryView(note, entry).title, '记第 1 天的警长投票')
  assert.equal(entryView(note, entry).hint, '一人一票。最高票只有一个时，会把他记成警长。')
  assert.equal(entryView(note, startEntry(note, 1)).hint, '警长的票算 1.5 票。最高票只有一个时，会把他记成被放逐。')
  entry = pickTarget(entry, 3)
  for (const no of [1, 2, 8, 10]) entry = toggleVoter(entry, no)
  entry = pickTarget(entry, 5)
  for (const no of [4, 6]) entry = toggleVoter(entry, no)
  assert.deepEqual(entryView(note, entry).lines.map((line) => [line.title, line.countText]), [['投 3 号', '4 票'], ['投 5 号', '2 票']], '原来的警长这时也只算一票')
  const done = finishEntry(note, entry)
  assert.deepEqual(dayOf(done, 1).election, { up: [], off: [], votes: [{ target: 3, voters: [1, 2, 8, 10] }, { target: 5, voters: [4, 6] }], elected: 3, lost: false })
  assert.deepEqual([seatOf(done, 3).sheriff, seatOf(done, 8).sheriff], [true, false], '当选的人成了警长')
  assert.deepEqual([dayOf(done, 1).votes, dayOf(done, 1).exiled, seatOf(done, 3).out], [[], 0, ''], '不动放逐票，也没人出局')
  assert.deepEqual(startEntry(done, 1, 'sheriff').groups, [{ target: 3, voters: [1, 2, 8, 10] }, { target: 5, voters: [4, 6] }], '再点开接着改')

  let tie = pickTarget(startEntry(note, 1, 'sheriff'), 3)
  tie = toggleVoter(pickTarget(toggleVoter(tie, 1), 5), 2)
  assert.deepEqual([dayOf(finishEntry(note, tie), 1).election.elected, seatOf(finishEntry(note, tie), 8).sheriff], [0, true], '平票不记警长，原来的不动')
})

test('aVoteKeepsTheWeightItHadThatDay', () => {
  // 票数按投票当时算：之后警徽移交给别人、警长出局，都不改已经记下的那一天
  const { note, entry } = dayOne()
  let done = finishEntry(note, entry)
  assert.equal(voteView(done, 1).groups[0].countText, '6.5 票 · 含警长')
  done = toggleSheriff(done, 9)
  assert.deepEqual(voteView(done, 1).groups.map((group) => group.countText), ['6.5 票 · 含警长', '3 票', '2 票'], '9 号后来才接的警徽，第 1 天他那一票还是 1 票')

  // 房间里：警长自己被投出去、警徽给了投票的人，那天的票数还是公布时的数
  const room = { ...fresh(), dayData: { 1: { votes: [{ target: 1, voters: [2, 3, 4], weight: 3 }], exiled: 1 } } }
  assert.equal(voteView(toggleSheriff(room, 3), 1).groups[0].countText, '3 票')
  // 旧笔记没存权重的，还是按现在的警长算
  const old = { ...fresh(), dayData: { 1: { votes: [{ target: 1, voters: [2, 3, 4] }] } } }
  assert.equal(voteView(toggleSheriff(old, 3), 1).groups[0].countText, '3.5 票 · 含警长')
})

test('votesAreDrawnLikeTheGameLogWithFacesAndMarkColours', () => {
  // 票型和房间里「记录」同一个画法：被投的人一行（头像 + 票条 + 票数），下面是投他的人的头像；
  // 圆形头像的边框颜色是我在座位上标的，房间里有头像，面杀没有头像就只有圆圈里的号码
  const { note, entry } = dayOne()
  const done = finishEntry(note, entry)
  const avatars = { 3: 'https://x/3.png', 5: 'https://x/5.png', 9: 'https://x/9.png' }
  const view = voteView(done, 1, { avatars })
  assert.deepEqual(view.groups.map((group) => [group.target, group.tone, group.avatar, group.pct, group.top]), [
    [5, 'wolf', 'https://x/5.png', 100, true], [3, 'god', 'https://x/3.png', 46, false], [9, 'wolf', 'https://x/9.png', 31, false],
  ], '票条按最高票的比例画，最高票标出来')
  assert.deepEqual(view.groups[1].voters, [
    { no: 4, tone: 'wolf', avatar: '' }, { no: 5, tone: 'wolf', avatar: 'https://x/5.png' }, { no: 9, tone: 'wolf', avatar: 'https://x/9.png' },
  ])
  assert.deepEqual(voteView(done, 1).groups[0].avatar, '', '面杀没有头像')

  const withAbstain = voteView({ ...done, dayData: { 1: { votes: [{ target: 5, voters: [1, 3] }], abstain: [4, 6] } } }, 1, { avatars })
  assert.deepEqual(withAbstain.abstain, [{ no: 4, tone: 'wolf', avatar: '' }, { no: 6, tone: 'none', avatar: '' }], '弃票的人也是一排头像')

  const election = { up: [3, 5], off: [], votes: [{ target: 3, voters: [1, 2, 4] }, { target: 5, voters: [6] }], elected: 3, lost: false }
  const elected = electionSection({ ...done, dayData: { 1: { election } } }, { avatars }).items[0]
  assert.deepEqual(elected.groups.map((group) => [group.target, group.avatar, group.pct, group.top]), [[3, 'https://x/3.png', 100, true], [5, 'https://x/5.png', 33, false]])
  assert.deepEqual(elected.up.map((person) => [person.no, person.avatar, person.off, person.elected]), [[3, 'https://x/3.png', false, true], [5, 'https://x/5.png', false, false]])
})
