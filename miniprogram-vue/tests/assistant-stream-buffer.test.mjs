import test from 'node:test'
import assert from 'node:assert/strict'

import { createAssistantStreamBuffer, shouldAutoScrollOnMessageAppend } from '../src/pages/assistant/stream-buffer.js'

test('should only auto scroll when message count increases', () => {
  assert.equal(shouldAutoScrollOnMessageAppend(0, 1), true)
  assert.equal(shouldAutoScrollOnMessageAppend(2, 2), false)
  assert.equal(shouldAutoScrollOnMessageAppend(3, 2), false)
})

test('should batch multiple deltas into one flush', () => {
  const scheduled = []
  const flushed = []
  const timerApi = {
    setTimeout(callback) {
      scheduled.push(callback)
      return scheduled.length
    },
    clearTimeout() {},
  }

  const buffer = createAssistantStreamBuffer((delta) => flushed.push(delta), {
    delayMs: 24,
    timerApi,
  })

  buffer.push('舞')
  buffer.push('者')
  buffer.push('技能')

  assert.equal(flushed.length, 0)
  assert.equal(scheduled.length, 1)

  scheduled[0]()

  assert.deepEqual(flushed, ['舞者技能'])
})

test('should flush pending delta before dispose', () => {
  const flushed = []
  let clearedTimer = null
  const timerApi = {
    setTimeout() {
      return 42
    },
    clearTimeout(timerId) {
      clearedTimer = timerId
    },
  }

  const buffer = createAssistantStreamBuffer((delta) => flushed.push(delta), {
    delayMs: 24,
    timerApi,
  })

  buffer.push('金')
  buffer.push('水')
  buffer.dispose()

  assert.deepEqual(flushed, ['金水'])
  assert.equal(clearedTimer, 42)
})
