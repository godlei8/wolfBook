import test from 'node:test'
import assert from 'node:assert/strict'
import { commonRules, commonRulesSummary } from '../src/utils/judge/common-rules.js'

test('commonRulesCoverTheSharedFlow', () => {
  // 通用规则从规则文件生成：六章都在，每章有内容
  const rules = commonRules()
  assert.deepEqual(rules.map((section) => section.title), ['胜负', '警长竞选', '白天发言', '放逐投票', '遗言', '狼人自爆'])
  assert.ok(rules.every((section) => section.items.length > 0))
  const words = rules.find((section) => section.title === '遗言').items
  assert.ok(words.includes('每人 120 秒，自爆的狼 60 秒。'), words.join(' / '))
  assert.equal(rules.flatMap((section) => section.items).some((item) => /\*\*|\]\(/.test(item)), false, '不带 markdown 记号')
})

test('commonRulesSummaryListsTheChapters', () => {
  assert.equal(commonRulesSummary(), '胜负 · 警长竞选 · 白天发言 · 放逐投票 · 遗言 · 狼人自爆')
  assert.equal(commonRulesSummary([{ title: '遗言', items: ['a'] }, { title: '空的', items: [] }, null]), '遗言')
  assert.deepEqual(commonRules([]), [])
})
