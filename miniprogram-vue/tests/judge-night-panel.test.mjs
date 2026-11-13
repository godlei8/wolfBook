import test from 'node:test'
import assert from 'node:assert/strict'
import { isStepHolder, nightArt, nightPanel, seatBadges } from '../src/utils/judge/night-panel.js'
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
  assert.equal(panel.knife.text, '7 号 · 2 票', '按多数；3 号点了空刀不计票')
  assert.deepEqual(panel.rows.map((row) => row.text), ['7 号', '7 号', '空刀', '还没选'])
  assert.equal(panel.rows[0].me, true)
  assert.deepEqual(panel.buttons.map((button) => [button.action, button.label, button.target]),
    [['none', '空刀', null], ['kill', '刀 5 号', 5], ['confirm', '确定', null]], '选过了就能确定')
  const tie = nightPanel(night('wolf', '狼人', me(1), {
    line: 'x',
    nightInfo: { teammateSeats: [2], wolfPicks: [{ seatNo: 1, targetSeatNo: 5, decided: true }, { seatNo: 2, targetSeatNo: 6, decided: true }] },
  }))
  assert.equal(tie.knife.text, '平票 · 随机')
  assert.equal(tie.knife.seat, null)
  const partial = nightPanel(night('wolf', '狼人', me(1), {
    line: 'x',
    nightInfo: { teammateSeats: [2], wolfPicks: [{ seatNo: 1, targetSeatNo: 5, decided: true }, { seatNo: 2, targetSeatNo: null, decided: false }] },
  }))
  assert.equal(partial.knife.seat, 5, '没选的狼不算，选了的那只控刀')
  const mixed = nightPanel(night('wolf', '狼人', me(1), {
    line: 'x',
    nightInfo: { teammateSeats: [2], wolfPicks: [{ seatNo: 1, targetSeatNo: 5, decided: true }, { seatNo: 2, targetSeatNo: null, decided: true }] },
  }))
  assert.equal(mixed.knife.text, '5 号 · 1 票', '点空刀的狼不计票')
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
  assert.equal(noAntidote.knifeText, '解药已用完，不再告知刀口')
  const noAntidoteNoKnife = nightPanel(night('witch', '女巫', me(3), { line: 'x', nightInfo: { knifeSeat: null, antidote: false, poison: true } }))
  assert.equal(noAntidoteNoKnife.knifeText, '解药已用完，不再告知刀口', '没解药时后端不下发刀口，不能显示成平安')
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

test('witchNeverSavesHerself', () => {
  // roles/女巫.md：全程不可自救；旧板子 / 旧房间存的 first_night 也不算
  const canSelfSave = (rules, currentDay = 1) => nightPanel(night('witch', '女巫', me(3), {
    line: 'x', nightInfo: { knifeSeat: 3, antidote: true, poison: true }, extra: { rules, currentDay },
  })).buttons.some((button) => button.action === 'save')
  assert.equal(canSelfSave({ witchSelfSave: 'never' }), false, '不能自救的板子首夜也不行')
  assert.equal(canSelfSave({}), false, '没设 = 默认不能自救')
  assert.equal(canSelfSave({ witchSelfSave: 'first_night' }), false, '旧房间存的首夜自救也不行')
  assert.equal(canSelfSave({ witchSelfSave: 'first_night' }, 2), false, '第二夜起不能自救')
  // 刀口不是自己时照样能救
  const other = nightPanel(night('witch', '女巫', me(3), { line: 'x', nightInfo: { knifeSeat: 7, antidote: true, poison: true }, extra: { rules: {} } }))
  assert.equal(other.buttons.some((button) => button.action === 'save'), true)
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


test('wolfStepConfirmAndReselect', () => {
  // 用户给定规则：选好后点确定，全员确定 5 秒后结束；确定之后可以重新选择
  const fresh = nightPanel(night('wolf', '狼人', me(1), { line: 'x', nightInfo: { teammateSeats: [2], wolfPicks: [
    { seatNo: 1, targetSeatNo: null, decided: false, confirmed: false },
    { seatNo: 2, targetSeatNo: 7, decided: true, confirmed: true },
  ] } }), 5)
  assert.deepEqual(fresh.buttons.map((button) => button.action), ['none', 'kill'], '没选不能确定')
  assert.deepEqual(fresh.rows.map((row) => row.text), ['还没选', '7 号 ✓'])
  const mine = nightPanel(night('wolf', '狼人', me(1), { line: 'x', nightInfo: { teammateSeats: [2], myAction: 'kill', myTarget: 7, myConfirmed: true, wolfPicks: [
    { seatNo: 1, targetSeatNo: 7, decided: true, confirmed: true },
    { seatNo: 2, targetSeatNo: 7, decided: true, confirmed: false },
  ] } }))
  assert.deepEqual(mine.buttons.map((button) => [button.action, button.label]), [['reselect', '重新选择']])
  assert.equal(mine.done, '已确定，等队友确定')
  const all = nightPanel(night('wolf', '狼人', me(1), { line: 'x', nightInfo: { teammateSeats: [2], myConfirmed: true, wolfPicks: [
    { seatNo: 1, targetSeatNo: 7, decided: true, confirmed: true },
    { seatNo: 2, targetSeatNo: null, decided: true, confirmed: true },
  ] } }))
  assert.equal(all.done, '都确定了，5 秒后结束')
})

test('armedMechWolfKnifesAloneInTheWolfStep', () => {
  // 狼队全出局：机械狼不在狼队里、没有 wolfPicks，只看自己的提交
  const mechSelf = { seatNo: 6, alive: true, self: true, mechanic: 'mech_wolf' }
  const before = nightPanel(night('wolf', '狼人', mechSelf, { line: '狼人请睁眼', nightInfo: { armed: true } }), 3)
  assert.deepEqual(before.buttons.map((button) => [button.action, button.target]), [['none', null], ['kill', 3]])
  assert.equal(before.knife.text, '还没选')
  const picked = nightPanel(night('wolf', '狼人', mechSelf, { line: 'x', nightInfo: { armed: true, myAction: 'kill', myTarget: 3, myConfirmed: false } }))
  assert.equal(picked.knife.text, '3 号 · 1 票')
  assert.ok(picked.buttons.some((button) => button.action === 'confirm'))
  const confirmed = nightPanel(night('wolf', '狼人', mechSelf, { line: 'x', nightInfo: { armed: true, myAction: 'kill', myTarget: 3, myConfirmed: true } }))
  assert.equal(confirmed.done, '都确定了，5 秒后结束')
})

const mech = (info, extra = {}) => night('mech_wolf', '机械狼', me(6), { line: '机械狼请睁眼', nightInfo: info, extra })
const actionsOf = (panel) => panel.buttons.filter((button) => button.action !== 'none').map((button) => button.action)

test('mechWolfLearnsFirst', () => {
  const info = { armed: false, learned: null, learnedEffective: false }
  const panel = nightPanel(mech(info), 3)
  assert.equal(panel.kind, 'mech_wolf')
  assert.equal(panel.learnedText, '还没学习，本局可以学一次')
  assert.equal(panel.armedText, '', '第一晚不报能不能开刀')
  assert.deepEqual(panel.buttons.map((button) => [button.action, button.label, button.target]),
    [['learn', '学习 3 号', 3], ['none', '今晚不行动', null]])
  assert.equal(nightPanel(mech(info), 6).buttons[0].disabled, true, '不能学习自己')
})

test('mechWolfToldItsKnifeStateFromTheSecondNight', () => {
  const armed = nightPanel(mech({ armed: true, learned: 'guard', learnedRole: '守卫', learnedEffective: true }, { currentDay: 2 }), 5)
  assert.equal(armed.armedText, '能开刀：刀口到狼人那一步再选')
  assert.deepEqual(actionsOf(armed), ['guard'], '带刀时技能照样在这一步用，刀口不在这一步')
  const not = nightPanel(mech({ armed: false, learned: 'guard', learnedRole: '守卫', learnedEffective: true }, { currentDay: 2 }), 5)
  assert.equal(not.armedText, '还不能开刀（狼人还在）')
})

test('mechWolfWithTheWolfSkillUsesThePierceKnifeInItsOwnStep', () => {
  const info = { armed: true, learned: 'wolf', learnedRole: '狼人', learnedEffective: true }
  assert.deepEqual(actionsOf(nightPanel(mech(info, { currentDay: 3 }), 5)), ['kill_pierce'])
  assert.deepEqual(actionsOf(nightPanel(mech({ ...info, armed: false }, { currentDay: 3 }), 5)), [], '狼人还在时没有破盾刀')
})

test('mechWolfSkillButtonsFollowWhatItLearned', () => {
  const day2 = (info, selection = 5) => nightPanel(mech(info, { currentDay: 2 }), selection)
  assert.deepEqual(actionsOf(day2({ armed: false, learned: 'psychic', learnedRole: '通灵师', learnedEffective: true })), ['check'])
  assert.deepEqual(actionsOf(day2({ armed: false, learned: 'witch', learnedRole: '女巫', learnedEffective: true, mechPoison: false })), [], '毒用过了')
  assert.deepEqual(actionsOf(day2({ armed: false, learned: 'witch', learnedRole: '女巫', learnedEffective: false, mechPoison: true })), [], '学的当晚还不能用')
  const blocked = day2({ armed: false, learned: 'guard', learnedRole: '守卫', learnedEffective: true, lastGuardSeat: 5 })
  assert.equal(blocked.buttons[0].disabled, true)
  assert.equal(blocked.buttons[0].label, '不能连守 5 号')
})

test('mechWolfIsDoneAfterSubmitting', () => {
  const info = { armed: false, learned: null, myAction: 'learn', myTarget: 3, checkedSeat: 3, identityResult: '女巫' }
  const panel = nightPanel(mech(info), 0)
  assert.equal(panel.done, '学习 3 号')
  assert.equal(panel.result, '3 号是女巫')
  assert.deepEqual(panel.buttons, [])
})

test('wolfKingMayPickHimself', () => {
  // 2026-11-12 用户改定：狼王可以自刀
  const king = { seatNo: 1, alive: true, self: true, mechanic: 'wolf_king' }
  const panel = nightPanel(night('wolf', '狼人', king, { line: 'x', nightInfo: { teammateSeats: [2], wolfPicks: [] } }), 1)
  const kill = panel.buttons.find((button) => button.action === 'kill')
  assert.equal(kill.disabled, false)
  assert.equal(kill.label, '刀 1 号')
})

test('panelsReflectWhatIJustDidTonight', () => {
  // 实测问题 1：机械狼刚学完，不能还写「还没学习」
  const learned = nightPanel(mech({ armed: false, learned: null, myAction: 'learn', myTarget: 3, checkedSeat: 3, identityResult: '女巫' }), 0)
  assert.equal(learned.learnedText, '今晚学了 3 号（女巫），下一晚生效')
  // 实测问题 4：女巫刚救完，解药不能还显示 1
  const saved = nightPanel(night('witch', '女巫', me(5), { line: 'x', nightInfo: { knifeSeat: 2, antidote: true, poison: true, myAction: 'save', myTarget: 2 } }))
  assert.deepEqual([saved.antidote.left, saved.poison.left], [0, 1])
  const poisoned = nightPanel(night('witch', '女巫', me(5), { line: 'x', nightInfo: { knifeSeat: 2, antidote: true, poison: true, myAction: 'poison', myTarget: 7 } }))
  assert.deepEqual([poisoned.antidote.left, poisoned.poison.left], [1, 0])
})

test('passingSaysWhatWasPassed', () => {
  // 实测问题 5：空守 / 不用药 / 不行动交完不能只写「已确认」
  assert.equal(nightPanel(night('guard', '守卫', me(5), { line: 'x', nightInfo: { myAction: 'none' } })).done, '今晚空守')
  assert.equal(nightPanel(night('witch', '女巫', me(5), { line: 'x', nightInfo: { antidote: true, poison: true, myAction: 'none' } })).done, '今晚不用药')
  assert.equal(nightPanel(mech({ armed: false, learned: null, myAction: 'none' })).done, '今晚不行动')
  assert.equal(nightPanel(night('hunter', '猎人', me(5), { line: 'x', nightInfo: { myAction: 'confirm' } })).done, '已确认')
})

test('hunterIsToldWhetherHeCanShootTonight', () => {
  const ok = nightPanel(night('hunter', '猎人', me(6), { line: 'x', nightInfo: { canShoot: true } }))
  assert.equal(ok.shootText, '今晚能开枪')
  assert.equal(ok.canShoot, true)
  assert.deepEqual(ok.buttons.map((button) => button.label), ['知道了'])
  const poisoned = nightPanel(night('hunter', '猎人', me(6), { line: 'x', nightInfo: { canShoot: false } }))
  assert.equal(poisoned.shootText, '被毒，今晚不能开枪')
  assert.equal(poisoned.canShoot, false)
  // 旧后端没下发开枪状态：不乱说
  assert.equal(nightPanel(night('hunter', '猎人', me(6), { line: 'x' })).shootText, '')
})
