import test from 'node:test'
import assert from 'node:assert/strict'

import { normalizeChatMessage } from '../src/pages/assistant/message-model.js'

test('streaming assistant message renders markdown as it arrives', () => {
  const normalized = normalizeChatMessage(
    {
      id: 'm1',
      role: 'ASSISTANT',
      content: '## 结论\n\n女巫可以...',
      contentFormat: 'MARKDOWN',
      citations: [],
      recommendedBoards: [],
      isStreaming: true,
    },
    {
      markdownRenderer: (content) => `markdown:${content}`,
      plainRenderer: () => {
        throw new Error('streaming message should not use plain renderer')
      },
    },
  )

  assert.equal(normalized.renderMode, 'markdown')
  assert.equal(normalized.renderedContent, 'markdown:## 结论\n\n女巫可以...')
})

test('completed assistant markdown message renders markdown once', () => {
  const normalized = normalizeChatMessage(
    {
      id: 'm2',
      role: 'ASSISTANT',
      content: '## 结论\n\n守卫和女巫通常会冲突。',
      contentFormat: 'MARKDOWN',
      citations: [],
      recommendedBoards: [],
      isStreaming: false,
    },
    {
      markdownRenderer: (content) => `markdown:${content}`,
      plainRenderer: () => {
        throw new Error('completed markdown message should not use plain renderer')
      },
    },
  )

  assert.equal(normalized.renderMode, 'markdown')
  assert.equal(normalized.renderedContent, 'markdown:## 结论\n\n守卫和女巫通常会冲突。')
})

test('history assistant messages render markdown right away', () => {
  const normalized = normalizeChatMessage(
    {
      id: 'm4',
      role: 'ASSISTANT',
      content: '**守卫**不能连续两晚守同一个人。',
      contentFormat: 'MARKDOWN',
    },
    { markdownRenderer: (content) => `markdown:${content}` },
  )

  assert.equal(normalized.renderMode, 'markdown')
  assert.equal(normalized.renderedContent, 'markdown:**守卫**不能连续两晚守同一个人。')
})

test('user messages stay plain text', () => {
  const normalized = normalizeChatMessage(
    { id: 'u1', role: 'USER', content: '<b>女巫</b>能自救吗' },
    {
      markdownRenderer: () => {
        throw new Error('user message should not be rendered as markdown')
      },
    },
  )

  assert.equal(normalized.renderMode, 'plain')
  assert.equal(normalized.renderedContent, '<b>女巫</b>能自救吗')
})

test('structured citations that duplicate recommended boards are hidden and only web citations remain visible', () => {
  const normalized = normalizeChatMessage(
    {
      id: 'm3',
      role: 'ASSISTANT',
      content: 'answer',
      contentFormat: 'MARKDOWN',
      recommendedBoards: [{ id: 7, name: '狼美人骑士' }],
      citations: [
        { sourceType: 'STRUCTURED', sourceId: '7', title: '板子卡片' },
        { sourceType: 'WEB', sourceId: 'w1', title: '网页来源' },
        { sourceType: 'DOCUMENT', sourceId: 'd1', title: '知识库文档' },
      ],
    },
    {
      markdownRenderer: () => 'markdown',
      plainRenderer: () => '',
    },
  )

  assert.deepEqual(
    normalized.visibleCitations,
    [{ sourceType: 'WEB', sourceId: 'w1', title: '网页来源' }],
  )
})
