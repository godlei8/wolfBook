import test from 'node:test'
import assert from 'node:assert/strict'

import { normalizeChatMessage } from '../src/pages/assistant/message-model.js'

test('streaming assistant message stays in plain render mode', () => {
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
      markdownRenderer: () => {
        throw new Error('streaming message should not render markdown')
      },
      plainRenderer: () => {
        throw new Error('streaming message should not render plain rich text either')
      },
    },
  )

  assert.equal(normalized.renderMode, 'plain')
  assert.equal(normalized.renderedContent, '## 结论\n\n女巫可以...')
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

test('long completed assistant markdown message defers heavy rendering automatically', () => {
  const normalized = normalizeChatMessage(
    {
      id: 'm2-long',
      role: 'ASSISTANT',
      content: '## 结论\n\n' + '这是一个很长的回答。'.repeat(80),
      contentFormat: 'MARKDOWN',
      citations: [],
      recommendedBoards: [],
      isStreaming: false,
    },
    {
      markdownRenderer: () => {
        throw new Error('long completed message should defer markdown rendering')
      },
      plainRenderer: () => {
        throw new Error('long completed message should not use plain renderer helper')
      },
    },
  )

  assert.equal(normalized.renderMode, 'plain')
  assert.equal(normalized.isRenderPending, true)
  assert.ok(normalized.renderedContent.startsWith('## 结论'))
})

test('deferred markdown mode keeps initial page entry cheap for historical assistant messages', () => {
  const normalized = normalizeChatMessage(
    {
      id: 'm4',
      role: 'ASSISTANT',
      content: '## 很长的历史回答\n\n' + '证据 '.repeat(200),
      contentFormat: 'MARKDOWN',
      citations: [],
      recommendedBoards: [],
      isStreaming: false,
    },
    {
      markdownRenderer: () => {
        throw new Error('deferred historical message should not render markdown immediately')
      },
      plainRenderer: () => {
        throw new Error('deferred historical message should not render plain rich text immediately')
      },
    },
    {
      deferMarkdown: true,
    },
  )

  assert.equal(normalized.renderMode, 'plain')
  assert.equal(normalized.isRenderPending, true)
  assert.ok(normalized.renderedContent.startsWith('## 很长的历史回答'))
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
