import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DEFAULT_ACCENT,
  FACTION_ACCENTS,
  normalizeRuleDetail,
  roleAccent,
  withAlpha,
} from '../src/utils/board-rules.js'

test('roleAccent prefers a valid theme color and normalizes its case', () => {
  assert.equal(roleAccent({ themeColor: '#7b4fd6', faction: '好人' }), '#7B4FD6')
})

test('roleAccent falls back to the faction color, then to the default', () => {
  assert.equal(roleAccent({ themeColor: 'purple', faction: '狼人' }), FACTION_ACCENTS['狼人'])
  assert.equal(roleAccent({ themeColor: null, faction: '第三方' }), FACTION_ACCENTS['第三方'])
  assert.equal(roleAccent({ faction: '未知' }), DEFAULT_ACCENT)
  assert.equal(roleAccent(null), DEFAULT_ACCENT)
})

test('withAlpha converts hex to rgba and survives bad input', () => {
  assert.equal(withAlpha('#C0262D', 0.2), 'rgba(192, 38, 45, 0.2)')
  assert.equal(withAlpha('red', 0.5), withAlpha(DEFAULT_ACCENT, 0.5))
})

test('missing rule detail becomes empty lists with no content', () => {
  const detail = normalizeRuleDetail(undefined)
  assert.deepEqual(detail, { overview: '', nightOrder: [], roleRules: [], sections: [], hasContent: false })
})

test('rule detail resolves portraits, accents and order, dropping empty entries', () => {
  const detail = normalizeRuleDetail(
    {
      overview: '  12 人标准局  ',
      nightOrder: [
        { roleId: 6, roleName: '狼人', portrait: '/uploads/wolf.png', themeColor: '#C0262D', action: ' 狼人刀人 ' },
        { roleId: 2, roleName: '女巫', portrait: null, themeColor: null, action: '   ' },
        { roleId: 1, roleName: '预言家', portrait: 'https://cdn/seer.png', themeColor: '#7B4FD6', action: '查验' },
      ],
      roleRules: [
        { roleId: 2, roleName: '女巫', items: ['首夜可自救', '  '] },
        { roleId: 3, roleName: '猎人', items: [''] },
      ],
      sections: [
        { title: '警长竞选', items: ['警长 1.5 票'] },
        { title: '  ', items: ['没有标题的章节会被丢掉'] },
      ],
    },
    (url) => (url && url.startsWith('/') ? `https://api.example.com${url}` : url),
  )

  assert.equal(detail.overview, '12 人标准局')
  assert.equal(detail.hasContent, true)
  assert.equal(detail.nightOrder.length, 2)
  assert.equal(detail.nightOrder[0].portrait, 'https://api.example.com/uploads/wolf.png')
  assert.equal(detail.nightOrder[0].action, '狼人刀人')
  assert.equal(detail.nightOrder[0].accent, '#C0262D')
  assert.equal(detail.nightOrder[1].roleName, '预言家')
  assert.equal(detail.nightOrder[1].order, 2)
  assert.deepEqual(detail.roleRules.map((note) => note.items), [['首夜可自救']])
  assert.equal(detail.roleRules[0].accent, DEFAULT_ACCENT)
  assert.deepEqual(detail.sections, [{ title: '警长竞选', items: ['警长 1.5 票'] }])
})

test('night steps without a theme color fall back to their faction color', () => {
  const detail = normalizeRuleDetail({ nightOrder: [{ roleId: 6, faction: '狼人', themeColor: null, action: '刀人' }] })
  assert.equal(detail.nightOrder[0].accent, FACTION_ACCENTS['狼人'])
})

test('server-provided order numbers are kept', () => {
  const detail = normalizeRuleDetail({ nightOrder: [{ roleId: 4, order: 3, action: '守人' }] })
  assert.equal(detail.nightOrder[0].order, 3)
})
