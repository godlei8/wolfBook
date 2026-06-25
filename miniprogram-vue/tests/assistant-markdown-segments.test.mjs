import test from 'node:test'
import assert from 'node:assert/strict'

import { markdownToRichTextSegments } from '../src/services/markdown.js'

test('markdown segments should split long answers into multiple progressive chunks', () => {
  const markdown = [
    '## 结论',
    '',
    '第一段内容。'.repeat(30),
    '',
    '### 规则拆解',
    '',
    '- 要点一',
    '- 要点二',
    '',
    '第二段内容。'.repeat(30),
  ].join('\n')

  const segments = markdownToRichTextSegments(markdown, { segmentCharLimit: 220 })

  assert.ok(Array.isArray(segments))
  assert.ok(segments.length > 1)
  assert.ok(segments.every((item) => typeof item === 'string' && item.length > 0))
})
