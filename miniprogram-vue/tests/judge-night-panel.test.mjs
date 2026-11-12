import test from 'node:test'
import assert from 'node:assert/strict'
import { isStepHolder, nightArt, nightPanel, seatBadges, witchSelfSaveText } from '../src/utils/judge/night-panel.js'
import { stageLayout } from '../src/utils/judge/stage.js'
import { countdown, phaseTitle } from '../src/utils/judge/room-view.js'

const players = Array.from({ length: 9 }, (_, i) => ({ seatNo: i + 1, nickname: `P${i + 1}`, alive: i + 1 !== 9, playing: true }))
const board = { roles: [{ name: '狼人', fullIllustration: '/wolf.png' }, { name: '女巫', fullIllustration: '/witch.png' }] }

function night(kind, roleName, self, { line = '', nightInfo = null, extra = {} } = {}) {
  return {
    roomStatus: 'playing', currentPhase: 'night_action', currentDay: 1, judgeViewer: false,
    rules: { witchSelfSave: 'first_night', witchOnePotionPerNight: true },
    currentStep: { index: 0, total: 4, kind, roleName, line, deadline: '2026-10-30T20:00:30' },
    phaseDeadline: '2026-10-30T20:00:30', paused: false,
    players, selfPlayer: self, nightInfo, ...extra,
  }
}
const me = (seatNo, alive = true) => ({ seatNo, alive, self: true })

test('wolfPanelShowsPicksAndTheKnife', () => {
  const info = {
    teammateSeats: [2, 3, 4],
    wolfPicks: [
      { seatNo: 1, targetSeatNo: 7, decided: true },
      { seatNo: 2, targetSeatNo: 7, decided: true },
      { seatNo: 3, targetSeatNo: null, decided: true },
      { seatNo: 4, targetSeatNo: null, decided: false },
    ],
  }
  const panel = nightPanel(night('wolf', '狼人', me(1), { line: '狼人请睁眼', nightInfo: info }), 5)
  assert.equal(panel.kind, 'wolf')
  assert.equal(panel.knife.text, '7 号 · 2 票')
  assert.deepEqual(panel.rows.map((row) => row.text), ['7 号', '7 号', '空刀', '还没选'])
  assert.equal(panel.rows[0].me, true)
  assert.deepEqual(panel.buttons.map((button) => [button.action, button.label, button.target]), [['none', '空刀', null], ['kill', '刀 5 号', 5]])
  const tie = nightPanel(night('wolf', '狼人', me(1), {
    line: 'x',
    nightInfo: { teammateSeats: [2], wolfPicks: [{ seatNo: 1, targetSeatNo: 5, decided: true }, { seatNo: 2, targetSeatNo: 6, decided: true }] },
  }))
  assert.equal(tie.knife.text, '平票 · 随机')
  assert.equal(tie.buttons[1].disabled, true, '没选座位不能刀')
  const empty = nightPanel(night('wolf', '狼人', me(1), { line: 'x', nightInfo: { teammateSeats: [], wolfPicks: [{ seatNo: 1, targetSeatNo: null, decided: true }] } }))
  assert.equal(empty.knife.text, '空刀')
  const badges = seatBadges(night('wolf', '狼人', me(1), { line: 'x', nightInfo: info }), 5)
  assert.equal(badges.get(2).mate, true)
  assert.equal(badges.get(7).votes, 2)
  assert.equal(badges.get(5).selected, true)
})

test('witchPanelHidesSaveWhenNoAntidote', () => {
  const full = nightPanel(night('witch', '女巫', me(3), { line: 'x', nightInfo: { knifeSeat: 7, antidote: true, poison: true } }), 5)
  assert.equal(full.kind, 'witch')
  assert.equal(full.knifeText, '7 号被刀')
  assert.deepEqual(full.buttons.map((button) => [button.action, button.label]), [['save', '救 7 号'], ['poison', '毒 5 号'], ['none', '不用药']])
  const noAntidote = nightPanel(night('witch', '女巫', me(3), { line: 'x', nightInfo: { knifeSeat: 7, antidote: false, poison: true } }))
  assert.equal(noAntidote.buttons.some((button) => button.action === 'save'), false)
  assert.equal(noAntidote.antidote.left, 0)
  const quiet = nightPanel(night('witch', '女巫', me(3), { line: 'x', nightInfo: { knifeSeat: null, antidote: true, poison: false } }))
  assert.equal(quiet.knifeText, '今晚平安')
  assert.deepEqual(quiet.buttons.map((button) => button.action), ['none'])
  const selfKnifeLater = nightPanel(night('witch', '女巫', me(3), {
    line: 'x', nightInfo: { knifeSeat: 3, antidote: true, poison: true }, extra: { currentDay: 2 },
  }))
  assert.equal(selfKnifeLater.buttons.some((button) => button.action === 'save'), false, '第二夜起不能自救')
  const used = nightPanel(night('witch', '女巫', me(3), { line: 'x', nightInfo: { knifeSeat: 7, antidote: true, poison: true, myAction: 'save', myTarget: 7 } }))
  assert.equal(used.done, '已救 7 号')
  assert.deepEqual(used.buttons, [], '一晚一瓶：用过就没有按钮')
})

test('witchSelfSaveFollowsTheBoard', () => {
  // 女巫自救跟板子走：默认不能自救；板子设了首夜可以自救的，只有第一夜能救自己
  const canSelfSave = (rules, currentDay = 1) => nightPanel(night('witch', '女巫', me(3), {
    line: 'x', nightInfo: { knifeSeat: 3, antidote: true, poison: true }, extra: { rules, currentDay },
  })).buttons.some((button) => button.action === 'save')
  assert.equal(canSelfSave({ witchSelfSave: 'never' }), false, '不能自救的板子首夜也不行')
  assert.equal(canSelfSave({}), false, '没设 = 默认不能自救')
  assert.equal(canSelfSave({ witchSelfSave: 'first_night' }), true, '首夜可以自救')
  assert.equal(canSelfSave({ witchSelfSave: 'first_night' }, 2), false, '第二夜起不能自救')
  // 刀口不是自己时照样能救
  const other = nightPanel(night('witch', '女巫', me(3), { line: 'x', nightInfo: { knifeSeat: 7, antidote: true, poison: true }, extra: { rules: {} } }))
  assert.equal(other.buttons.some((button) => button.action === 'save'), true)
})

test('witchSelfSaveTextForTheLegacyJudgeDesk', () => {
  assert.equal(witchSelfSaveText({ rules: { witchSelfSave: 'first_night' }, currentDay: 1 }), '首夜可以自救')
  assert.equal(witchSelfSaveText({ rules: { witchSelfSave: 'first_night' }, currentDay: 2 }), '首夜已过，不能自救')
  assert.equal(witchSelfSaveText({ rules: { witchSelfSave: 'never' }, currentDay: 1 }), '本板子不能自救')
  assert.equal(witchSelfSaveText({ currentDay: 1 }), '本板子不能自救')
})

test('seerPanelShowsTheResultAfterChecking', () => {
  const before = nightPanel(night('seer', '预言家', me(4), { line: 'x' }), 6)
  assert.deepEqual(before.buttons.map((button) => button.label), ['查验 6 号'])
  const after = nightPanel(night('seer', '预言家', me(4), { line: 'x', nightInfo: { checkedSeat: 6, checkResult: '狼人', myAction: 'check', myTarget: 6 } }))
  assert.equal(after.result, '6 号是狼人')
  assert.deepEqual(after.buttons, [])
})

test('guardCannotPickLastNightsSeat', () => {
  const panel = nightPanel(night('guard', '守卫', me(2), { line: 'x', nightInfo: { lastGuardSeat: 5 } }), 5)
  const guardButton = panel.buttons.find((button) => button.action === 'guard')
  assert.equal(guardButton.disabled, true)
  assert.equal(guardButton.label, '不能连守 5 号')
  assert.equal(panel.buttons.find((button) => button.action === 'none').label, '空守')
  assert.equal(nightPanel(night('guard', '守卫', me(2), { line: 'x', nightInfo: { lastGuardSeat: 5 } }), 6).buttons[0].label, '守 6 号')
})

test('hunterAndGenericOnlyConfirm', () => {
  assert.deepEqual(nightPanel(night('hunter', '猎人', me(6), { line: 'x' })).buttons.map((button) => button.action), ['confirm'])
  assert.deepEqual(nightPanel(night('generic', '魔术师', me(6), { line: 'x' })).buttons.map((button) => button.action), ['confirm'])
})

test('nightScreensMatchExceptThePanel', () => {
  const now = Date.UTC(2026, 9, 30, 12, 0, 0)
  const holder = night('witch', '女巫', me(3), { line: '女巫请睁眼', nightInfo: { knifeSeat: 7, antidote: true, poison: true } })
  const villager = night('witch', '女巫', me(4))
  const other = night('witch', '女巫', me(5))
  const shared = (snapshot) => ({
    title: phaseTitle(snapshot),
    clock: countdown(snapshot.phaseDeadline, now, snapshot.paused),
    art: nightArt(snapshot, board),
    seats: [...seatBadges(snapshot, 0).entries()].map(([seat, badge]) => [seat, badge.dead, badge.disabled]),
    // 牌桌上所有人都看得见的座位标记（队友红点、狼刀票数只给狼队那一步的狼，见 seatBadges）
    stage: stageLayout(snapshot, 'night').sides.map((side) => side.seats.map((seat) => [seat.seatNo, seat.dead, seat.sheriff, seat.role])),
  })
  assert.deepEqual(shared(villager), shared(holder))
  assert.deepEqual(shared(other), shared(holder))
  assert.equal(shared(holder).art, '/witch.png')
  assert.ok(nightPanel(holder))
  assert.equal(nightPanel(villager), null)
  assert.equal(isStepHolder(holder), true)
  assert.equal(isStepHolder(villager), false)
})

test('deadPlayersSeeTheSameNightScreen', () => {
  const deadWitch = night('witch', '女巫', me(9, false), { line: '女巫请睁眼' })
  const villager = night('witch', '女巫', me(4))
  assert.equal(nightPanel(deadWitch), null)
  assert.equal(isStepHolder(deadWitch), false)
  assert.equal(phaseTitle(deadWitch), phaseTitle(villager))
  assert.deepEqual([...seatBadges(deadWitch, 0).values()], [...seatBadges(villager, 0).values()])
})

test('judgeIsNeverAHolder', () => {
  const judge = night('wolf', '狼人', { seatNo: null, judgeObserver: true, self: true }, { line: '狼人请睁眼', extra: { judgeViewer: true } })
  assert.equal(isStepHolder(judge), false)
  assert.equal(nightPanel(judge), null)
})

test('wolfKnifeBeforeAnyoneChooses', () => {
  const info = { teammateSeats: [2], wolfPicks: [{ seatNo: 1, targetSeatNo: null, decided: false }, { seatNo: 2, targetSeatNo: null, decided: false }] }
  assert.equal(nightPanel(night('wolf', '狼人', me(1), { line: 'x', nightInfo: info })).knife.text, '还没选')
})

// ---- 通灵师、机械狼、白痴空步、狼王不能自刀（08 / 01 / 03）----

test('psychicPanelShowsTheExactRole', () => {
  const before = nightPanel(night('psychic', '通灵师', me(1), { line: '通灵师请睁眼', nightInfo: {} }), 3)
  assert.equal(before.kind, 'psychic')
  assert.deepEqual(before.buttons.map((button) => [button.action, button.label, button.target]), [['check', '查验 3 号', 3]])
  const after = nightPanel(night('psychic', '通灵师', me(1), {
    line: 'x', nightInfo: { checkedSeat: 5, identityResult: '狼人', myAction: 'check', myTarget: 5 },
  }))
  assert.equal(after.result, '5 号是狼人')
  assert.deepEqual(after.buttons, [])
})

test('idleStepOnlyConfirms', () => {
  const panel = nightPanel(night('idle', '白痴', me(1), { line: '白痴请睁眼' }))
  assert.equal(panel.kind, 'idle')
  assert.deepEqual(panel.buttons.map((button) => [button.action, button.label]), [['confirm', '我知道了']])
})

const mech = (info, extra = {}) => night('mech_wolf', '机械狼', me(6), { line: '机械狼请睁眼', nightInfo: info, extra })

test('mechWolfLearnsFirst', () => {
  const info = { armed: false, learned: null, learnedEffective: false, myActions: [] }
  const empty = nightPanel(mech(info), 3, {})
  assert.equal(empty.kind, 'mech_wolf')
  assert.equal(empty.learnedText, '还没学习，本局可以学一次')
  assert.equal(empty.armedText, '', '第一晚不报带没带刀')
  const pick = empty.buttons.find((button) => button.slot === 'learn')
  assert.deepEqual([pick.action, pick.label, pick.target], ['pick', '学习 3 号', 3])
  assert.ok(empty.buttons.some((button) => button.action === 'none' && button.label === '今晚不行动'))
  assert.equal(empty.buttons.find((button) => button.action === 'submit').disabled, true)
  const drafted = nightPanel(mech(info), 0, { learn: 3 })
  assert.deepEqual(drafted.rows.map((row) => row.text), ['学习 3 号'])
  const submit = drafted.buttons.find((button) => button.action === 'submit')
  assert.equal(submit.disabled, false)
  assert.deepEqual(submit.actions, [{ actionType: 'learn', targetSeatNo: 3 }])
})

test('mechWolfWithTheWolfSkillAndArmsGetsTwoKnives', () => {
  const info = { armed: true, learned: 'wolf', learnedRole: '狼人', learnedEffective: true, myActions: [] }
  const panel = nightPanel(mech(info, { currentDay: 3 }), 5, { kill: 4 })
  assert.equal(panel.armedText, '你现在带刀')
  assert.equal(panel.learnedText, '已学到 狼人')
  assert.deepEqual(panel.buttons.filter((button) => button.action === 'pick').map((button) => button.slot), ['kill', 'kill_pierce'])
  assert.deepEqual(panel.buttons.find((button) => button.action === 'submit').actions, [{ actionType: 'kill', targetSeatNo: 4 }])
})

test('mechWolfSkillButtonsFollowWhatItLearned', () => {
  const slots = (info, selection = 5, day = 2) => nightPanel(mech(info, { currentDay: day }), selection, {}).buttons
    .filter((button) => button.action === 'pick')
  assert.deepEqual(slots({ armed: false, learned: 'psychic', learnedRole: '通灵师', learnedEffective: true }).map((b) => b.slot), ['check'])
  assert.deepEqual(slots({ armed: false, learned: 'witch', learnedRole: '女巫', learnedEffective: true, mechPoison: false }), [], '毒用过了')
  assert.deepEqual(slots({ armed: false, learned: 'witch', learnedRole: '女巫', learnedEffective: false, mechPoison: true }), [], '学的当晚还不能用')
  const guard = slots({ armed: false, learned: 'guard', learnedRole: '守卫', learnedEffective: true }, 5)
  assert.equal(guard[0].slot, 'guard')
  const repeat = nightPanel(mech({ armed: false, learned: 'guard', learnedRole: '守卫', learnedEffective: true }, { currentDay: 3 }), 5, {})
  // 上一夜守的人从 lastGuardSeat 来
  const blocked = nightPanel(mech({ armed: false, learned: 'guard', learnedRole: '守卫', learnedEffective: true, lastGuardSeat: 5 }, { currentDay: 3 }), 5, {})
  assert.equal(repeat.buttons.find((b) => b.slot === 'guard').disabled, false)
  assert.equal(blocked.buttons.find((b) => b.slot === 'guard').disabled, true)
  assert.equal(blocked.buttons.find((b) => b.slot === 'guard').label, '不能连守 5 号')
})

test('mechWolfIsDoneAfterSubmitting', () => {
  const info = { armed: false, learned: null, myActions: [{ actionType: 'learn', targetSeatNo: 3 }], checkedSeat: 3, identityResult: '女巫' }
  const panel = nightPanel(mech(info), 0, {})
  assert.equal(panel.done, '已提交：学习 3 号')
  assert.equal(panel.result, '3 号是女巫')
  assert.deepEqual(panel.buttons, [])
})

test('wolfKingCannotPickHimself', () => {
  const king = { seatNo: 1, alive: true, self: true, mechanic: 'wolf_king' }
  const panel = nightPanel(night('wolf', '狼人', king, { line: 'x', nightInfo: { teammateSeats: [2], wolfPicks: [] } }), 1)
  const kill = panel.buttons.find((button) => button.action === 'kill')
  assert.equal(kill.disabled, true)
  assert.equal(kill.label, '狼王不能自刀')
})
