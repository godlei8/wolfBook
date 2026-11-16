import test from 'node:test'
import assert from 'node:assert/strict'
import { boardConfigRows, boardRuleIntro, boardRuleLine, buildCreatePayload, canChooseJoined, defaultRoomRules, judgeModeRow, stepRule } from '../src/utils/judge/create-room.js'

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
  assert.equal(boardRuleLine(city), '屠城 · 女巫不能自救 · 一晚一瓶', '全程不可自救，旧板子存的首夜自救也不显示')
  assert.equal(boardRuleLine(board({ winCondition: 'side', witchSelfSave: 'never', witchOnePotionPerNight: false })), '屠边 · 女巫不能自救 · 一晚一瓶',
    '不能自救、一晚一瓶都是全局规则，板子上写的别的值不算')
  assert.equal(boardRuleLine(board({ winCondition: 'side' })), '屠边 · 女巫不能自救 · 一晚一瓶', '板子没设 = 默认不能自救')
  assert.equal(boardRuleLine(board({ winCondition: 'side' }, { roles: [{ name: '狼人' }, { name: '平民' }] })), '屠边')
})

// 建房第二步「板子配置」：跟板子走、房间里改不了的玩法规则，只读
test('boardConfigShowsTheRulesThatComeWithTheBoard', () => {
  const role = (roleId, name, faction) => ({ roleId, name, faction, count: 1 })
  const mech = {
    roles: [role(1, '守卫', '好人'), role(2, '狼人', '狼人'), role(3, '女巫', '好人'), role(4, '通灵师', '好人'), role(5, '机械狼', '狼人'), role(6, '猎人', '好人')],
    ruleDetail: { judgeRules: { winCondition: 'side', poisonBounce: true } },
  }
  assert.deepEqual(boardConfigRows(mech), [
    { label: '胜负', value: '屠边' },
    { label: '女巫', value: '不能自救 · 一晚一瓶' },
    { label: '弹毒', value: '开' },
    { label: '夜间顺序', value: '机械狼 → 守卫 → 狼人 → 女巫 → 通灵师 → 猎人' },
  ])
  const plain = { roles: [role(1, '狼人', '狼人'), role(2, '平民', '好人')], winCondition: '屠城', ruleDetail: {} }
  assert.deepEqual(boardConfigRows(plain), [
    { label: '胜负', value: '屠城' },
    { label: '夜间顺序', value: '狼人' },
  ], '没有女巫、机械狼就不显示这两行')
  assert.deepEqual(boardConfigRows(null), [])
})

test('boardRuleIntroShowsTheBoardsOwnRules', () => {
  // 建房页「板子规则」：玩法概述 + 每个角色的规则（第一条常显，其余展开看）+ 通用规则章节
  const detail = board({}, {
    ruleDetail: {
      overview: ' 12 人标准局 ',
      roleRules: [
        { roleId: 2, roleName: '女巫', portrait: '/w.png', accent: '#111111', campColor: '#222222', items: ['一晚只能用一瓶', '全程不可自救', '没解药不告知刀口'] },
        { roleId: 9, roleName: '平民', items: ['没有技能'] },
      ],
      sections: [{ title: '遗言', items: ['每人 120 秒'] }],
      judgeRules: {},
    },
  })
  const intro = boardRuleIntro(detail)
  assert.equal(intro.overview, '12 人标准局')
  assert.deepEqual(intro.roles.map((role) => [role.roleId, role.name, role.lead, role.more]), [
    [2, '女巫', '一晚只能用一瓶', ['全程不可自救', '没解药不告知刀口']],
    [9, '平民', '没有技能', []],
  ])
  assert.equal(intro.roles[0].portrait, '/w.png')
  assert.equal(intro.roles[0].moreLabel, '还有 2 条')
  assert.equal(intro.roles[1].moreLabel, '')
  assert.deepEqual(intro.sections, [{ title: '遗言', value: '', items: ['每人 120 秒'] }])
  assert.equal(intro.hasContent, true)
  assert.deepEqual(intro.rows.map((row) => row.label), ['胜负', '女巫', '夜间顺序'], '没有「胜负」章节时胜负照旧占一行')
})

test('boardRuleIntroFollowsTheLineupOrderAndFoldsTheWinRule', () => {
  // 角色规则按阵容的顺序排（和上面的头像一致）；有「胜负」章节时，胜负那一行并进章节标题，不重复占一行
  const detail = {
    roles: [{ roleId: 6, name: '狼人', faction: '狼人' }, { roleId: 9, name: '平民', faction: '好人' }],
    ruleDetail: {
      roleRules: [{ roleId: 9, roleName: '平民', items: ['没有技能'] }, { roleId: 6, roleName: '狼人', items: ['每晚刀一人'] }],
      sections: [{ title: '胜负', items: ['屠边：……'] }, { title: '遗言', items: ['每人 120 秒'] }],
      judgeRules: { winCondition: 'side' },
    },
  }
  const intro = boardRuleIntro(detail)
  assert.deepEqual(intro.roles.map((role) => role.name), ['狼人', '平民'])
  assert.deepEqual(intro.sections.map((section) => [section.title, section.value]), [['胜负', '屠边'], ['遗言', '']])
  assert.deepEqual(intro.rows.map((row) => row.label), ['夜间顺序'])
})

test('boardRuleIntroIsEmptyWhenTheBoardHasNoRulesYet', () => {
  const intro = boardRuleIntro(board())
  assert.deepEqual([intro.overview, intro.roles, intro.sections, intro.hasContent], ['', [], [], false])
  assert.deepEqual(intro.rows.map((row) => row.label), ['胜负', '女巫', '夜间顺序'], '没有规则介绍时配置行照旧')
  assert.equal(boardRuleIntro(null).hasContent, false)
})

test('judgeModeRowIsOneCompactLine', () => {
  // 法官模式收成一行两个小按钮 + 一句说明；无法官不能选时按钮变灰，说明写原因
  const full = judgeModeRow(board(), 'observer')
  assert.deepEqual(full.options.map((item) => [item.value, item.label, item.on, item.disabled]), [
    ['observer', '有法官', true, false],
    ['joined', '无法官', false, false],
  ])
  assert.equal(full.hint, '你当法官，不拿牌；能看全场、暂停和点杀')
  assert.equal(judgeModeRow(board(), 'joined').hint, '你也拿牌，系统当法官；建议连蓝牙音箱')
  const partial = judgeModeRow(board({}, { judgeSupportLevel: 'partial' }), 'observer')
  assert.equal(partial.options[1].disabled, true)
  assert.equal(partial.hint, '你当法官，不拿牌；这个板子有系统还不会结算的角色，只能开有法官')
})
