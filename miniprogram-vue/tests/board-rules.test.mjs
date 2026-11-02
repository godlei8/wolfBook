import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DEFAULT_ACCENT,
  FACTION_ACCENTS,
  campBreakdown,
  campTextColor,
  groupLineup,
  normalizeRuleDetail,
  pickLineupArt,
  roleAccent,
  withAlpha,
} from '../src/utils/board-rules.js'

const role = (roleId, name, faction, roleType, extra = {}) => ({
  roleId,
  name,
  faction,
  roleType,
  portrait: `/p/${roleId}.webp`,
  fullIllustration: `/f/${roleId}.webp`,
  ...extra,
})

test('pickLineupArt puts the special wolf in the middle and the first two gods on the sides', () => {
  const art = pickLineupArt([
    role(1, '预言家', '好人', '神职', { themeColor: '#7B4FD6' }),
    role(2, '女巫', '好人', '神职'),
    role(3, '猎人', '好人', '神职'),
    role(9, '平民', '好人', '平民'),
    role(6, '狼人', '狼人', '狼人'),
    role(11, '黑狼王', '狼人', '狼人'),
  ])
  assert.deepEqual(art.map((item) => item.name), ['预言家', '黑狼王', '女巫'])
  assert.deepEqual(art.map((item) => item.center), [false, true, false])
  assert.equal(art[0].image, '/f/1.webp')
  assert.equal(art[0].accent, '#7B4FD6')
  assert.equal(art[1].accent, FACTION_ACCENTS['狼人'])
})

test('pickLineupArt can lay out a five-card fan with alternating sides', () => {
  const art = pickLineupArt(
    [
      role(1, '预言家', '好人', '神职'),
      role(2, '女巫', '好人', '神职'),
      role(3, '猎人', '好人', '神职'),
      role(8, '白痴', '好人', '神职'),
      role(9, '平民', '好人', '平民'),
      role(6, '狼人', '狼人', '狼人'),
    ],
    5,
  )
  assert.deepEqual(art.map((item) => item.name), ['猎人', '预言家', '狼人', '女巫', '白痴'])
  assert.deepEqual(art.map((item) => item.slot), ['l2', 'l1', 'c', 'r1', 'r2'])
  assert.deepEqual(pickLineupArt([role(6, '狼人', '狼人', '狼人')]).map((item) => item.slot), ['c'])
})

test('pickLineupArt falls back to the plain wolf, portraits, and villagers when needed', () => {
  const art = pickLineupArt([
    role(9, '平民', '好人', '平民'),
    role(6, '狼人', '狼人', '狼人', { fullIllustration: null }),
    role(1, '预言家', '好人', '神职'),
  ])
  assert.deepEqual(art.map((item) => item.name), ['预言家', '狼人', '平民'])
  assert.equal(art[1].image, '/p/6.webp')
})

test('campBreakdown counts cards per camp in a fixed order and drops empty camps', () => {
  const segments = campBreakdown([
    role(6, '狼人', '狼人', '狼人', { count: 3 }),
    role(1, '预言家', '好人', '神职', { count: 1 }),
    role(9, '平民', '好人', '平民', { count: 4 }),
    role(7, '狼美人', '狼人', '狼人', { count: 1 }),
    role(2, '女巫', '好人', '神职', { count: 1 }),
    role(16, '爱神', '第三方', '第三方', { count: 0 }),
    null,
  ])
  assert.deepEqual(segments.map((s) => [s.label, s.count]), [['神职', 2], ['平民', 4], ['狼人', 4]])
  assert.deepEqual(campBreakdown(undefined), [])
})

test('groupLineup groups roles by camp in display order with card totals', () => {
  const groups = groupLineup([
    role(6, '狼人', '狼人', '狼人', { count: 3 }),
    role(1, '预言家', '好人', '神职', { count: 1 }),
    role(9, '平民', '好人', '平民', { count: 4 }),
    role(7, '狼美人', '狼人', '狼人', { count: 1 }),
  ])
  assert.deepEqual(groups.map((g) => [g.label, g.count, g.roles.map((r) => r.name)]), [
    ['神职', 1, ['预言家']],
    ['平民', 4, ['平民']],
    ['狼人', 4, ['狼人', '狼美人']],
  ])
})

test('campTextColor follows the camp, and night steps carry it', () => {
  assert.equal(campTextColor({ faction: '狼人' }), '#FF7468')
  assert.equal(campTextColor({ faction: '好人', roleType: '平民' }), '#8FD0A6')
  assert.equal(campTextColor({ faction: '第三方' }), '#F7A8CB')
  assert.equal(campTextColor(null), '#E9C46A')
  const detail = normalizeRuleDetail({ nightOrder: [{ roleId: 6, faction: '狼人', action: '刀人' }] })
  assert.equal(detail.nightOrder[0].campColor, '#FF7468')
})

test('pickLineupArt skips roles without any art and returns fewer cards for small lineups', () => {
  assert.deepEqual(pickLineupArt([]), [])
  assert.deepEqual(pickLineupArt(undefined), [])
  const art = pickLineupArt([
    role(1, '预言家', '好人', '神职', { portrait: '', fullIllustration: '' }),
    role(6, '狼人', '狼人', '狼人'),
    role(2, '女巫', '好人', '神职'),
  ])
  assert.deepEqual(art.map((item) => item.name), ['女巫', '狼人'])
  assert.equal(art[1].center, true)
})

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
  assert.deepEqual(detail, { overview: '', nightOrder: [], roleRules: [], sections: [], judgeRules: null, hasContent: false })
})

test('rule detail keeps the board judge rules for the create page', () => {
  const judgeRules = { winCondition: 'side', speechSeconds: 90 }
  assert.equal(normalizeRuleDetail({ judgeRules }).judgeRules, judgeRules)
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
