import test from 'node:test'
import assert from 'node:assert/strict'

import { buildChatViewportState } from '../src/pages/assistant/chat-state.mjs'

test('reset mode should clear scroll target and jump back to top', () => {
  assert.deepEqual(
    buildChatViewportState('reset', 'assistant-1', 640),
    {
      scrollTop: 0,
      scrollIntoView: '',
    },
  )
})

test('append mode should keep scroll-top and follow the latest message', () => {
  assert.deepEqual(
    buildChatViewportState('append', 'assistant-9', 640),
    {
      scrollTop: 640,
      scrollIntoView: 'msg-assistant-9',
    },
  )
})
