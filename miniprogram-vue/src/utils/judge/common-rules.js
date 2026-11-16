// 通用规则（所有板子一样）：小程序里点「通用规则」弹窗看的内容。数据在 common-rules.data.js，
// 由 scripts/judge-rules-sql.py 从规则文件生成；这里只管怎么显示（纯函数，有单测）。
import { COMMON_RULES } from './common-rules.data.js'

/** 弹窗里的章节：标题 + 条目，空的不要。 */
export function commonRules(source = COMMON_RULES) {
  return (source || [])
    .filter((section) => section?.title && (section.items || []).length)
    .map((section) => ({ title: section.title, items: section.items }))
}

/** 入口那一行的小字：里面有哪几章。 */
export function commonRulesSummary(source = COMMON_RULES) {
  return commonRules(source).map((section) => section.title).join(' · ')
}
