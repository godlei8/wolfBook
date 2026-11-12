// 后台板子工具里和后端同口径的电子法官逻辑（Node 内置测试运行器，Node 23+ 直接加载 .ts）：node --test "tests/*.test.mjs"
import test from 'node:test'
import assert from 'node:assert/strict'
import { MECHANIC_LABELS, buildJudgeRules, defaultNightOrder, hasMechWolf, hasWitch, isWolfTeam } from '../src/utils/board.ts'

const role = (id, name, faction, mechanic) => ({ id, name, faction, mechanic })

test('wolfKingJoinsTheWolfTeam', () => {
  assert.equal(isWolfTeam(role(11, '黑狼王', '狼人', 'wolf_king')), true)
  assert.equal(MECHANIC_LABELS.wolf_king, '狼王')
})

test('defaultNightOrderKeepsTheWolfStepWhenOnlyTheWolfKingIsLeft', () => {
  // 阵容里狼队只有狼王：照样有狼刀那一步，排在守卫之后、女巫之前
  const roles = new Map([
    [4, role(4, '守卫', '好人', 'guard')],
    [11, role(11, '黑狼王', '狼人', 'wolf_king')],
    [2, role(2, '女巫', '好人', 'witch')],
  ])
  const steps = defaultNightOrder([{ roleId: 2, count: 1 }, { roleId: 11, count: 1 }, { roleId: 4, count: 1 }], roles)
  assert.deepEqual(steps.map((step) => step.roleId), [4, 11, 2])
})

test('wolfKingAndWolvesShareOneStep', () => {
  const roles = new Map([[6, role(6, '狼人', '狼人', 'wolf')], [11, role(11, '黑狼王', '狼人', 'wolf_king')]])
  const steps = defaultNightOrder([{ roleId: 11, count: 1 }, { roleId: 6, count: 3 }], roles)
  assert.deepEqual(steps.map((step) => step.roleId), [6])
})

test('witchSelfSaveIsSavedOnTheBoardAndOtherRulesAreKept', () => {
  // 女巫默认不能自救，板子可以设成首夜可以自救；保存时板子原来的节奏项原样带上，别被编辑页冲掉
  const existing = { sheriffDefault: false, speechSeconds: 90, witchSelfSave: 'first_night', witchOnePotionPerNight: true }
  assert.deepEqual(buildJudgeRules(existing, false), { sheriffDefault: false, speechSeconds: 90, witchSelfSave: 'never' })
  assert.deepEqual(buildJudgeRules(null, true), { witchSelfSave: 'first_night' })
})

test('hasWitchLooksAtTheWitchMechanic', () => {
  const roles = new Map([[2, role(2, '女巫', '好人', 'witch')], [9, role(9, '平民', '好人', 'villager')]])
  assert.equal(hasWitch([{ roleId: 2, count: 1 }, { roleId: 9, count: 3 }], roles), true)
  assert.equal(hasWitch([{ roleId: 9, count: 3 }], roles), false)
})

test('poisonBounceIsABoardSpecificRule', () => {
  // 弹毒只存「开」；关掉时把原来存的开去掉，节奏项照旧带上
  assert.deepEqual(buildJudgeRules({ speechSeconds: 90 }, false, true), { speechSeconds: 90, witchSelfSave: 'never', poisonBounce: true })
  assert.deepEqual(buildJudgeRules({ speechSeconds: 90, poisonBounce: true }, false, false), { speechSeconds: 90, witchSelfSave: 'never' })
})

test('mechWolfIsNotInTheWolfTeamAndWakesFirst', () => {
  assert.equal(isWolfTeam(role(12, '机械狼', '狼人', 'mech_wolf')), false)
  assert.equal(MECHANIC_LABELS.mech_wolf, '机械狼')
  assert.equal(MECHANIC_LABELS.psychic, '通灵师')
  const roles = new Map([
    [14, role(14, '通灵师', '好人', 'psychic')],
    [2, role(2, '女巫', '好人', 'witch')],
    [3, role(3, '猎人', '好人', 'hunter')],
    [4, role(4, '守卫', '好人', 'guard')],
    [6, role(6, '狼人', '狼人', 'wolf')],
    [12, role(12, '机械狼', '狼人', 'mech_wolf')],
    [9, role(9, '平民', '好人', 'villager')],
  ])
  const lineup = [9, 14, 2, 3, 4, 6, 12].map((roleId) => ({ roleId, count: 1 }))
  const steps = defaultNightOrder(lineup, roles)
  assert.deepEqual(steps.map((step) => step.roleId), [12, 4, 6, 2, 14, 3])
  assert.match(steps[4].action, /^通灵师请睁眼/)
  assert.equal(hasMechWolf(lineup, roles), true)
  assert.equal(hasMechWolf([{ roleId: 2, count: 1 }], roles), false)
})

test('idiotHasAnIdleStepAtTheEnd', () => {
  const roles = new Map([[6, role(6, '狼人', '狼人', 'wolf')], [8, role(8, '白痴', '好人', 'idiot')], [3, role(3, '猎人', '好人', 'hunter')]])
  const steps = defaultNightOrder([{ roleId: 8, count: 1 }, { roleId: 6, count: 2 }, { roleId: 3, count: 1 }], roles)
  assert.deepEqual(steps.map((step) => step.roleId), [6, 3, 8])
})
