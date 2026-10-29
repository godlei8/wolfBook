import test from 'node:test'
import assert from 'node:assert/strict'

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
