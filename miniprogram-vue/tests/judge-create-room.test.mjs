import test from 'node:test'
import assert from 'node:assert/strict'
import { boardRuleLine, buildCreatePayload, canChooseJoined, defaultRoomRules, stepRule } from '../src/utils/judge/create-room.js'

const board = (judgeRules = {}, extra = {}) => ({
  id: 2,
  judgeSupportLevel: 'full',
  roles: [{ name: '女巫' }, { name: '狼人' }],
  ruleDetail: { judgeRules },
  ...extra,
})

test('defaultRulesComeFromTheBoard', () => {
  assert.equal(defaultRoomRules(board({ speechSeconds: 90 })).speechSeconds, 90)
  assert.deepEqual(defaultRoomRules(board()), {
    sheriffEnabled: true, speechSeconds: 120, sheriffSpeechSeconds: 150, wolfSeconds: 60, roleSeconds: 30,
  })
  assert.equal(defaultRoomRules(board({ sheriffDefault: false })).sheriffEnabled, false)
  assert.equal(defaultRoomRules(null).wolfSeconds, 60)
})

test('stepRuleClampsToItsRange', () => {
  assert.equal(stepRule(120, 'wolfSeconds', 1), 120)
  assert.equal(stepRule(60, 'wolfSeconds', 1), 70)
  assert.equal(stepRule(15, 'roleSeconds', -1), 15)
  assert.equal(stepRule(30, 'roleSeconds', -1), 25)
  assert.equal(stepRule(120, 'speechSeconds', 1), 150)
  assert.equal(stepRule(300, 'sheriffSpeechSeconds', 1), 300)
  assert.equal(stepRule(30, 'speechSeconds', -1), 30)
})

test('payloadAlwaysAsksForTheNewFlow', () => {
  const rules = defaultRoomRules(board())
  assert.deepEqual(buildCreatePayload(board(), 'joined', rules), { boardId: 2, judgeMode: 'joined', autoJudge: true, rules })
  assert.equal(buildCreatePayload(board(), 'observer', rules).autoJudge, true)
})

test('joinedOnlyForFullBoards', () => {
  assert.equal(canChooseJoined(board()), true)
  assert.equal(canChooseJoined(board({}, { judgeSupportLevel: 'partial' })), false)
  assert.equal(canChooseJoined(null), false)
})

test('boardRuleLineIsReadOnlyText', () => {
  const city = board({ winCondition: 'city', witchSelfSave: 'first_night', witchOnePotionPerNight: true })
  assert.equal(boardRuleLine(city), '屠城 · 女巫只首夜可自救 · 一晚一瓶')
  assert.equal(boardRuleLine(board({ winCondition: 'side', witchSelfSave: 'never', witchOnePotionPerNight: false })), '屠边 · 女巫不能自救 · 一晚一瓶',
    '电子法官每人每晚只记一次行动，一晚两瓶用不了，不写出来')
  assert.equal(boardRuleLine(board({ winCondition: 'side' }, { roles: [{ name: '狼人' }, { name: '平民' }] })), '屠边')
})
