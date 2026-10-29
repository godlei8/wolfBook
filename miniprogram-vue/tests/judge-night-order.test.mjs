import test from 'node:test'
import assert from 'node:assert/strict'
import { buildNightSteps, checkResult, isWolfTeam, stepHolders, witchPotions } from '../src/utils/judge/night-order.js'

const roles = [
  { roleId: 1, name: '预言家', faction: '好人' },
  { roleId: 2, name: '女巫', faction: '好人' },
  { roleId: 3, name: '猎人', faction: '好人' },
  { roleId: 4, name: '狼人', faction: '狼人' },
  { roleId: 5, name: '白狼王', faction: '狼人' },
  { roleId: 6, name: '平民', faction: '好人' },
  { roleId: 7, name: '石像鬼', faction: '狼人' },
]

test('without a configured night order the default order is used and wolves share one step', () => {
  const steps = buildNightSteps({ roles, ruleDetail: { nightOrder: [] } })
  assert.deepEqual(steps.map((step) => step.roleName), ['狼人', '石像鬼', '女巫', '预言家', '猎人'])
  assert.equal(steps[0].kind, 'wolf')
  assert.equal(steps[0].roleId, 4)
  assert.equal(steps[2].kind, 'witch')
  assert.deepEqual(steps.map((step) => step.order), [1, 2, 3, 4, 5])
  assert.ok(steps.every((step) => step.action))
})

test('a configured night order is kept, with later wolf steps folded into the wolf team step', () => {
  const steps = buildNightSteps({
    roles,
    ruleDetail: {
      nightOrder: [
        { roleId: 4, roleName: '狼人', faction: '狼人', action: '狼人请睁眼' },
        { roleId: 5, roleName: '白狼王', faction: '狼人', action: '白狼王请睁眼' },
        { roleId: 1, roleName: '预言家', faction: '好人', action: '查验一名玩家' },
      ],
    },
  })
  assert.deepEqual(steps.map((step) => step.roleName), ['狼人', '预言家'])
  assert.equal(steps[1].action, '查验一名玩家')
  assert.equal(steps[1].kind, 'seer')
})

test('wolf team holders include every wolf except solo wolves', () => {
  const players = [
    { seatNo: 1, roleId: 4, roleName: '狼人', faction: '狼人' },
    { seatNo: 2, roleId: 5, roleName: '白狼王', faction: '狼人' },
    { seatNo: 3, roleId: 7, roleName: '石像鬼', faction: '狼人' },
    { seatNo: 4, roleId: 2, roleName: '女巫', faction: '好人' },
  ]
  const [wolfStep] = buildNightSteps({ roles, ruleDetail: { nightOrder: [] } })
  assert.deepEqual(stepHolders(wolfStep, players).map((player) => player.seatNo), [1, 2])
  assert.equal(isWolfTeam(players[2]), false)
})

test('witch potions are spent once used on any night', () => {
  assert.deepEqual(witchPotions([], [5]), { antidote: 1, poison: 1 })
  assert.deepEqual(witchPotions([{ actorSeatNo: 5, actionType: '解药' }, { actorSeatNo: 2, actionType: '毒药' }], [5]), { antidote: 0, poison: 1 })
})

test('seer check result follows the faction', () => {
  assert.equal(checkResult({ faction: '狼人' }), '狼人（查杀）')
  assert.equal(checkResult({ faction: '好人' }), '好人（金水）')
})
