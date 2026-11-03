// 后台板子工具里和后端同口径的电子法官逻辑（Node 内置测试运行器，Node 23+ 直接加载 .ts）：node --test "tests/*.test.mjs"
import test from 'node:test'
import assert from 'node:assert/strict'
import { MECHANIC_LABELS, buildJudgeRules, defaultNightOrder, hasWitch, isWolfTeam } from '../src/utils/board.ts'

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
