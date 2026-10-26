import test from 'node:test'
import assert from 'node:assert/strict'

import { createJudgeVoicePlayer } from '../src/utils/judge/voice.js'

test('judge voice player degrades gracefully when WechatSI plugin is unavailable', () => {
  const previousRequirePlugin = globalThis.requirePlugin
  const previousUni = globalThis.uni

  globalThis.requirePlugin = () => {
    throw new Error('plugin WechatSI is not registered')
  }
  globalThis.uni = {
    createInnerAudioContext() {
      return {
        stop() {},
        destroy() {},
      }
    },
  }

  try {
    const player = createJudgeVoicePlayer()
    assert.equal(player.supported, false)
  } finally {
    if (previousRequirePlugin === undefined) {
      delete globalThis.requirePlugin
    } else {
      globalThis.requirePlugin = previousRequirePlugin
    }

    if (previousUni === undefined) {
      delete globalThis.uni
    } else {
      globalThis.uni = previousUni
    }
  }
})
