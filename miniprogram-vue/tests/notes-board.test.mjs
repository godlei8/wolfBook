import test from 'node:test'
import assert from 'node:assert/strict'
import { createNote, dayOf, sayOf, seatOf } from '../src/utils/notes/model.js'
import {
  addTargetTag, daySummary, markPanel, seatCards, setGuess, setJudgment, setSayText,
  toggleClaim, toggleOut, togglePeace, toggleSheriff, toggleTag, wolfPit,
} from '../src/utils/notes/board.js'

const fresh = (extra = {}) => createNote({
  sessionId: 's1', boardMode: 'library', boardId: 2, boardName: '预女猎白', playerCount: 12, wolves: 4,
  claims: ['预言家', '女巫', '猎人', '白痴', '平民'], now: '2026-11-16T12:00:00.000Z', ...extra,
})
const card = (note, no, day = 1, ui = {}) => seatCards(note, day, ui).find((item) => item.no === no)

test('aMarkedSeatIsTintedSoItShowsAtAGlance', () => {
  // 记了就看得见：标成狼整张卡变红，好人绿，神职金
  let note = setGuess(fresh(), 9, 'wolf')
  note = setGuess(note, 3, 'god')
  assert.equal(seatCards(note, 1).length, 12)
  assert.deepEqual([card(note, 9).tone, card(note, 9).guessShort], ['wolf', '狼'])
  assert.deepEqual([card(note, 3).tone, card(note, 3).guessShort], ['god', '神'])
  assert.deepEqual([card(note, 1).tone, card(note, 1).guessShort], ['none', ''])
  assert.equal(card(setGuess(note, 9, 'wolf'), 9).tone, 'none', '再点一次同一个就取消')
  assert.equal(card(setGuess(note, 9, ''), 9).tone, 'none', '清除')
})

test('claimsShowOnTheSeatAndTwoOfAKindAreAStandoff', () => {
  let note = toggleClaim(fresh(), 3, '预言家')
  assert.equal(card(note, 3).claim, '跳预言家')
  note = toggleClaim(note, 9, '预言家')
  assert.deepEqual([card(note, 3).claim, card(note, 9).claim], ['对跳预言家', '对跳预言家'])
  note = toggleClaim(note, 9, '预言家')
  assert.deepEqual([card(note, 3).claim, card(note, 9).claim], ['跳预言家', ''], '再点一次取消')
})

test('whatASeatDidThatDayIsOneLineOnTheCard', () => {
  let note = addTargetTag(fresh(), 1, 4, 'stand', 9)
  note = addTargetTag(note, 1, 4, 'step', 3)
  assert.equal(card(note, 4).line, '站 9 · 踩 3')
  assert.equal(card(note, 4, 2).line, '', '那是第 1 天的，第 2 天的卡上没有')
  note = addTargetTag(note, 1, 4, 'stand', 9)
  assert.equal(card(note, 4).line, '踩 3', '同一个再点一次取消')
  assert.equal(card(addTargetTag(note, 1, 4, 'stand', 4), 4).line, '踩 3 · 站 4', '也可以指自己（比如对跳时说站自己）')
  note = toggleTag(note, 1, 6, '划水')
  assert.equal(card(note, 6).line, '划水')
  note = setSayText(note, 1, 6, '  一直在跟票  ')
  assert.equal(card(note, 6).line, '划水 · 一直在跟票')
  assert.deepEqual(sayOf(note, 1, 6), { tags: ['划水'], text: '一直在跟票' })
})

test('whoIsOutAndHowIsOnTheCard', () => {
  let note = toggleOut(fresh(), 7, 'night', 1)
  assert.deepEqual([card(note, 7).gone, card(note, 7).line, card(note, 7).out], [true, '首夜出局', '首夜出局'])
  note = toggleOut(note, 5, 'exile', 1)
  assert.deepEqual([card(note, 5).gone, card(note, 5).line], [true, '第 1 天放逐'])
  note = toggleOut(note, 2, 'night', 2)
  assert.deepEqual([card(note, 2, 1).gone, card(note, 2, 2).gone, card(note, 2, 2).line], [false, true, '第 2 夜出局'], '看第 1 天时他还在')
  assert.equal(card(toggleOut(note, 5, 'exile', 1), 5).gone, false, '再点一次取消')
  assert.equal(card(toggleOut(note, 5, 'night', 1), 5).line, '首夜出局', '换一种出局方式')
})

test('onlyOneSheriff', () => {
  let note = toggleSheriff(fresh(), 3)
  assert.equal(card(note, 3).sheriff, true)
  note = toggleSheriff(note, 8)
  assert.deepEqual([seatOf(note, 3).sheriff, seatOf(note, 8).sheriff], [false, true])
  assert.equal(seatOf(toggleSheriff(note, 8), 8).sheriff, false)
})

test('wolfPitSumsUpTheSeatsMarkedAsWolves', () => {
  // 狼坑不用单独填：座位上标了几个狼，它自己汇总
  assert.deepEqual(wolfPit(fresh()), { seats: [], text: '点座位，标出你怀疑的人' })
  let note = setGuess(setGuess(fresh(), 9, 'wolf'), 4, 'wolf')
  note = toggleOut(setGuess(note, 5, 'wolf'), 5, 'exile', 1)
  assert.deepEqual(wolfPit(note).seats, [{ no: 4, gone: false }, { no: 5, gone: true }, { no: 9, gone: false }])
  assert.equal(wolfPit(note).text, '4 狼还差 1 个')
  note = setGuess(note, 12, 'wolf')
  assert.equal(wolfPit(note).text, '4 狼标齐了')
  assert.equal(wolfPit(setGuess(note, 1, 'wolf')).text, '标了 5 个，板子只有 4 狼')
  assert.equal(wolfPit(setGuess(fresh({ wolves: 0 }), 2, 'wolf')).text, '标了 1 个', '自定义板子不知道有几狼')
})

test('markPanelShowsWhatIsOnForTheSeat', () => {
  let note = toggleClaim(setGuess(fresh(), 9, 'wolf'), 9, '预言家')
  note = toggleSheriff(addTargetTag(note, 1, 9, 'kill', 3), 9)
  const panel = markPanel(note, 9, 1)
  assert.equal(panel.title, '9 号')
  assert.deepEqual(panel.guesses.map((item) => [item.label, item.on]), [['狼人', true], ['好人', false], ['神职', false], ['清除', false]])
  assert.deepEqual(panel.claims.filter((item) => item.on).map((item) => item.value), ['预言家'])
  assert.deepEqual(panel.claims.map((item) => item.value), ['预言家', '女巫', '猎人', '白痴', '平民'], '能跳的身份跟板子走')
  assert.deepEqual(panel.tags, ['查杀 3'])
  assert.deepEqual(panel.actions.map((item) => item.label), ['站边', '踩人', '报金水', '报查杀', '划水', '+ 写一句'])
  assert.deepEqual(panel.status.map((item) => [item.kind, item.label, item.on]), [
    ['night', '夜里出局', false], ['exile', '被放逐', false], ['shot', '被带走', false], ['boom', '自爆', false], ['sheriff', '当警长', true],
  ])

  const picking = markPanel(note, 9, 1, 'stand')
  assert.equal(picking.picker.title, '9 号站谁')
  assert.equal(picking.actions.find((item) => item.kind === 'stand').on, true)
  assert.deepEqual(markPanel(fresh({ claims: [] }), 1, 1).claims.map((item) => item.value), ['预言家', '女巫', '猎人', '守卫', '白痴', '平民'], '不知道板子时给常见的')
})

test('judgmentIsPerDay', () => {
  let note = setJudgment(fresh(), 1, ' 4、5、9 像一起的 ')
  assert.equal(dayOf(note, 1).judgment, '4、5、9 像一起的')
  assert.equal(dayOf(note, 2).judgment, '')
  note = setJudgment(note, 1, '')
  assert.equal(dayOf(note, 1).judgment, '')
})

test('seatCardsCarryTheUiState', () => {
  const cards = seatCards(setGuess(fresh({ playerCount: 6 }), 2, 'good'), 1, { selected: 2, speaking: 4, me: 5, names: { 2: '团子' } })
  assert.equal(cards.length, 6)
  assert.deepEqual(cards.filter((item) => item.selected).map((item) => item.no), [2])
  assert.deepEqual(cards.filter((item) => item.speaking).map((item) => item.no), [4])
  assert.deepEqual(cards.filter((item) => item.me).map((item) => item.no), [5])
  assert.equal(cards[1].name, '团子')
})



test('shotAndBoomAreRecordedOnTheDayTheyHappen', () => {
  let note = toggleOut(fresh(), 6, 'shot', 1)
  note = toggleOut(note, 9, 'boom', 2)
  assert.deepEqual([card(note, 6).gone, card(note, 6).out], [true, '第 1 天被带走'])
  assert.deepEqual([card(note, 9, 1).gone, card(note, 9, 2).out], [false, '第 2 天自爆'])
  assert.equal(markPanel(note, 6, 1).status.find((item) => item.kind === 'shot').on, true)
  assert.equal(markPanel(note, 9, 2).status.find((item) => item.kind === 'boom').on, true)
  assert.equal(markPanel(note, 9, 1).status.find((item) => item.kind === 'boom').on, false, '是第 2 天爆的')
})





test('marksOnASeatAreShortColouredChips', () => {
  // 号码块上：站 / 踩 / 金水 / 查杀 / 划水各有颜色，最多两格；放不下的收成「+N」
  let note = addTargetTag(fresh(), 1, 4, 'stand', 9)
  assert.deepEqual(card(note, 4).marks, [{ text: '站9', tone: 'stand' }])
  note = addTargetTag(note, 1, 4, 'step', 3)
  assert.deepEqual(card(note, 4).marks, [{ text: '站9', tone: 'stand' }, { text: '踩3', tone: 'step' }])
  note = toggleTag(note, 1, 4, '划水')
  assert.deepEqual(card(note, 4).marks, [{ text: '站9', tone: 'stand' }, { text: '+2', tone: 'more' }])
  let seer = addTargetTag(addTargetTag(fresh(), 1, 3, 'gold', 5), 1, 3, 'kill', 12)
  assert.deepEqual(card(seer, 3).marks, [{ text: '金5', tone: 'gold' }, { text: '杀12', tone: 'kill' }])
  seer = setSayText(toggleTag(fresh(), 1, 6, '划水'), 1, 6, '一直在跟票')
  assert.deepEqual(card(seer, 6).marks, [{ text: '划水', tone: 'idle' }, { text: '注', tone: 'note' }], '写了一句话的有个「注」')
})

test('anUnmarkedSeatIsAQuietTile', () => {
  // 没标过的座位只有一个号码（房间里多头像和昵称），标了任何东西才变成有内容的卡
  const note = fresh()
  assert.equal(card(note, 4).blank, true)
  assert.equal(card(setGuess(note, 4, 'wolf'), 4).blank, false)
  assert.equal(card(toggleClaim(note, 4, '女巫'), 4).blank, false)
  assert.equal(card(toggleTag(note, 1, 4, '划水'), 4).blank, false)
  assert.equal(card(toggleOut(note, 4, 'exile', 1), 4).blank, false)
  assert.equal(card(toggleSheriff(note, 4), 4).blank, true, '警长是角上的徽标，不占卡里的位置')
  const inRoom = card(note, 4, 1, { names: { 4: '团子' }, avatars: { 4: 'https://x/a.png' } })
  assert.deepEqual([inRoom.blank, inRoom.name, inRoom.avatar], [true, '团子', 'https://x/a.png'])
  assert.equal(card(toggleClaim(toggleClaim(note, 3, '预言家'), 9, '预言家'), 3).standoff, true, '对跳的标红')
})

test('theDayPageSumsUpLastNightAndTheSheriff', () => {
  // 没有单独的夜晚页：夜里谁出局记在天亮的那一天，那一天顶上一行说昨夜和警长
  assert.deepEqual(daySummary(fresh(), 1), { text: '昨夜还没记', nightMissing: true, peace: false, canPeace: true })
  const note = toggleSheriff(toggleOut(fresh(), 7, 'night', 1), 3)
  assert.deepEqual(daySummary(note, 1), { text: '昨夜 7 号出局 · 警长 3 号', nightMissing: false, peace: false, canPeace: false }, '已经记了有人出局，就不再给「平安夜」')
  assert.equal(daySummary(toggleOut(note, 2, 'night', 2), 2).text, '昨夜 2 号出局 · 警长 3 号')
  assert.deepEqual(daySummary(fresh(), 1, { room: true }), { text: '', nightMissing: false, peace: false, canPeace: false }, '房间里不催你记，等自动填')
  assert.equal(daySummary(toggleSheriff(fresh(), 3), 1, { room: true }).text, '警长 3 号')
})

test('aPeacefulNightIsOneTap', () => {
  // 面杀：昨夜没人出局，点一下「平安夜」；之后又标了人夜里出局就不算了
  let note = togglePeace(fresh(), 1)
  assert.deepEqual([daySummary(note, 1).text, daySummary(note, 1).peace], ['昨夜平安夜', true])
  assert.equal(daySummary(togglePeace(note, 1), 1).peace, false, '再点一次取消')
  note = toggleOut(note, 7, 'night', 1)
  assert.deepEqual([daySummary(note, 1).text, daySummary(note, 1).peace], ['昨夜 7 号出局', false])
  note = togglePeace(note, 1)
  assert.deepEqual([daySummary(note, 1).text, seatOf(note, 7).out], ['昨夜平安夜', ''], '记成平安夜，那一夜标的出局就去掉')
  assert.equal(markPanel(toggleOut(fresh(), 7, 'night', 2), 7, 2).status[0].on, true)
  assert.equal(markPanel(toggleOut(fresh(), 7, 'night', 2), 7, 1).status[0].on, false, '是第 2 夜死的，不是首夜')
})

test('afterStandOrStepYouPickTheSeatRightInThePanel', () => {
  // 点了「站边 / 踩人 / 报金水 / 报查杀」：面板里直接给一排号码选，不用滚回上面去点座位
  assert.equal(markPanel(fresh(), 4, 1).picker, null, '没在等选人时没有这一排')
  let note = toggleOut(setGuess(setGuess(fresh({ playerCount: 6 }), 2, 'wolf'), 3, 'god'), 5, 'night', 1)
  const picker = markPanel(note, 4, 1, 'stand').picker
  assert.equal(picker.title, '4 号站谁')
  assert.deepEqual(picker.seats.map((seat) => seat.no), [1, 2, 3, 4, 5, 6], '所有座位都能选，包括他自己')
  assert.deepEqual(picker.seats.map((seat) => seat.tone), ['none', 'wolf', 'god', 'none', 'none', 'none'], '号码的边框颜色还是我标的')
  assert.deepEqual(picker.seats.filter((seat) => seat.gone).map((seat) => seat.no), [5], '出局的人变淡，但还能选（可以站一个死掉的预言家）')
  note = addTargetTag(note, 1, 4, 'stand', 3)
  assert.deepEqual(markPanel(note, 4, 1, 'stand').picker.seats.filter((seat) => seat.on).map((seat) => seat.no), [3], '已经记过的那个亮着，再点一次取消')
  assert.deepEqual(markPanel(note, 4, 1, 'step').picker.seats.filter((seat) => seat.on), [], '站和踩分开记')
  assert.equal(markPanel(note, 4, 1, 'kill').picker.title, '4 号查杀谁')
})

test('pastDaysDoNotClaimTodaysSheriff', () => {
  // 往回翻到前几天：顶上那一行不拿现在的警长去说当时。那天有人当选就说谁当选
  let note = { ...fresh(), days: 3 }
  note = { ...toggleSheriff(note, 11), dayData: { 1: { election: { up: [1, 2], off: [], votes: [], elected: 1, lost: false } } } }
  assert.equal(daySummary(note, 3, { room: true }).text, '警长 11 号', '最新的一天说现在的警长')
  assert.equal(daySummary(note, 1, { room: true }).text, '1 号当选警长', '第 1 天当选的是 1 号，警徽是后来才到 11 号手上的')
  assert.equal(daySummary(note, 2, { room: true }).text, '', '第 2 天没有竞选，就不提警长')
})
