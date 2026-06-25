import test from 'node:test'
import assert from 'node:assert/strict'

import { normalizeChatMessage } from '../src/pages/assistant/message-model.js'

test('assistant message normalization works when optional assistant metadata is omitted entirely', () => {
  const normalized = normalizeChatMessage(
    {
      id: 'm5',
      role: 'ASSISTANT',
      content: '## answer\n\nkeep only the basic answer body',
      contentFormat: 'MARKDOWN',
      isStreaming: false,
    },
    {
      markdownRenderer: (content) => `markdown:${content}`,
      plainRenderer: () => '',
    },
  )

  assert.equal(normalized.renderMode, 'markdown')
  assert.equal(normalized.renderedContent, 'markdown:## answer\n\nkeep only the basic answer body')
  assert.deepEqual(normalized.visibleCitations, [])
})
