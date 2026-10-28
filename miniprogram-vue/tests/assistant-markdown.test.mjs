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

test('bullet without a space before Chinese text is still a list item', async () => {
  const { markdownToRichText } = await import('../src/services/markdown.js')
  const html = markdownToRichText('### 狼王守卫\n-经典高配板子\n- 守卫很关键')
  assert.match(html, /<ul[^>]*><li[^>]*>经典高配板子<\/li><li[^>]*>守卫很关键<\/li><\/ul>/)
})

test('heading style keeps quotes balanced so rich-text reads the whole style', async () => {
  const { markdownToRichText } = await import('../src/services/markdown.js')
  const html = markdownToRichText('## 推荐结果')
  const style = html.match(/<h2 style="([^"]*)">/)
  assert.ok(style, 'h2 style attribute should not be cut by inner quotes')
  assert.match(style[1], /font-family:'Noto Serif SC'/)
})
