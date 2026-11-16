import test from 'node:test'
import assert from 'node:assert/strict'
import { createNote, sayOf } from '../src/utils/notes/model.js'
import { markPanel, toggleClaim } from '../src/utils/notes/board.js'
import { applyDockChip, roomSpeaker, speechDock } from '../src/utils/notes/speech.js'
import { createNoteSaver } from '../src/utils/notes/saver.js'

const fresh = () => createNote({ sessionId: 's1', boardMode: 'custom', boardName: 'x', playerCount: 12, now: '2026-11-16T12:00:00.000Z' })

test('roomSpeakerIsWhoeverIsTalkingNow', () => {
  const speech = { order: [2, 1, 12, 4, 3], index: 3 }
  assert.equal(roomSpeaker({ roomStatus: 'playing', currentPhase: 'day_speech', speech }), 4)
  assert.equal(roomSpeaker({ roomStatus: 'playing', currentPhase: 'sheriff_speech', speech }), 4)
  assert.equal(roomSpeaker({ roomStatus: 'playing', currentPhase: 'day_speech', speech: { order: [2], index: 1 } }), 0, '都说完了')
  assert.equal(roomSpeaker({ roomStatus: 'playing', currentPhase: 'exile_vote', speech }), 0, '投票时没人发言')
  assert.equal(roomSpeaker({ roomStatus: 'playing', currentPhase: 'skill_pending', currentPending: { kind: 'last_words', seatNo: 7 } }), 7, '留遗言也算')
  assert.equal(roomSpeaker({ roomStatus: 'playing', currentPhase: 'skill_pending', currentPending: null }), 0)
  assert.equal(roomSpeaker(null), 0)
})

test('theDockOffersTheSeatsIMarkedAsClaimingSomething', () => {
  // 发言快捷条：X 是我标了「他说自己是什么」的座位（最多三个），点一下就记到发言人的座位上
  let note = toggleClaim(toggleClaim(fresh(), 3, '预言家'), 9, '预言家')
  const dock = speechDock(note, 2, 4)
  assert.equal(dock.title, '4 号正在发言')
  assert.deepEqual(dock.chips.map((chip) => chip.label), ['站 3 号', '站 9 号', '踩 3 号', '踩 9 号', '跳身份', '划水', '+ 写一句'])
  note = applyDockChip(note, 2, 4, dock.chips[1])
  note = applyDockChip(note, 2, 4, dock.chips[2])
  assert.deepEqual(sayOf(note, 2, 4).tags, ['站 9', '踩 3'])
  assert.deepEqual(speechDock(note, 2, 4).chips.filter((chip) => chip.on).map((chip) => chip.label), ['站 9 号', '踩 3 号'])
  note = applyDockChip(note, 2, 4, speechDock(note, 2, 4).chips.find((chip) => chip.kind === 'idle'))
  assert.deepEqual(sayOf(note, 2, 4).tags, ['站 9', '踩 3', '划水'])
  assert.equal(speechDock(note, 2, 0), null, '没人发言就没有快捷条')
})

test('theDockDoesNotOfferTheSpeakerHimself', () => {
  const note = toggleClaim(toggleClaim(fresh(), 3, '预言家'), 9, '预言家')
  assert.deepEqual(speechDock(note, 1, 9).chips.map((chip) => chip.label), ['站 3 号', '踩 3 号', '跳身份', '划水', '+ 写一句'])
})

test('withNoClaimsYetTheDockAsksToPickASeat', () => {
  const dock = speechDock(fresh(), 1, 4)
  assert.deepEqual(dock.chips.map((chip) => [chip.kind, chip.label, chip.target]), [
    ['stand', '站边…', 0], ['step', '踩人…', 0], ['claim', '跳身份', 0], ['idle', '划水', 0], ['text', '+ 写一句', 0],
  ])
  assert.equal(applyDockChip(fresh(), 1, 4, dock.chips[0]), null, '要再点座位的，交给页面去选')
  assert.equal(applyDockChip(fresh(), 1, 4, dock.chips[2]), null)
})

test('inARoomThePanelHasNoStatusButtons', () => {
  // 法官房间里出局、警长是自动记的，不能手改
  assert.deepEqual(markPanel(fresh(), 3, 1, '', { room: true }).status, [])
  assert.equal(markPanel(fresh(), 3, 1).status.length, 5)
})

test('theSaverWaitsForAPauseThenSavesOnce', async () => {
  // 每次改动先改画面；0.8 秒内没有新改动再整份保存一次；离开时把没存的存掉
  const saved = []
  const states = []
  let timerFn = null
  const saver = createNoteSaver({
    save: async (note) => { saved.push(note.n) },
    onState: (state) => states.push(state),
    setTimer: (fn) => { timerFn = fn; return 1 },
    clearTimer: () => { timerFn = null },
  })
  saver.touch({ n: 1 })
  saver.touch({ n: 2 })
  assert.deepEqual(saved, [], '还在等')
  await timerFn()
  assert.deepEqual(saved, [2], '只存最后一份')
  assert.deepEqual(states, ['saving', 'saved'])
  saver.touch({ n: 3 })
  await saver.flush()
  assert.deepEqual(saved, [2, 3], '离开时补存')
  await saver.flush()
  assert.deepEqual(saved, [2, 3], '没有新改动不重复存')
})

test('aFailedSaveIsRetriedOnTheNextFlush', async () => {
  const saved = []
  const states = []
  let fail = true
  const saver = createNoteSaver({
    save: async (note) => { if (fail) throw new Error('offline'); saved.push(note.n) },
    onState: (state) => states.push(state),
    setTimer: () => 1,
    clearTimer: () => {},
  })
  saver.touch({ n: 1 })
  await saver.flush()
  assert.deepEqual(states, ['saving', 'failed'])
  fail = false
  await saver.flush()
  assert.deepEqual(saved, [1])
  assert.equal(states[states.length - 1], 'saved')
})
