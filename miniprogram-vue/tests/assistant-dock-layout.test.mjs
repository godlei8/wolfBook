import test from 'node:test'
import assert from 'node:assert/strict'

import { buildAssistantDockStyle, resolveAssistantDockTopRpx } from '../src/utils/assistant-dock.js'

test('resolveAssistantDockTopRpx clamps to the visible viewport and converts px to rpx by window width', () => {
  // rpx 按屏幕宽度换算：375px 宽的屏幕上 1px = 2rpx
  assert.equal(resolveAssistantDockTopRpx({ clientY: 20, windowWidth: 375, windowHeight: 812 }), 176)
  assert.equal(resolveAssistantDockTopRpx({ clientY: 760, windowWidth: 375, windowHeight: 812 }), 1344)
  assert.equal(resolveAssistantDockTopRpx({ clientY: 420, windowWidth: 375, windowHeight: 812 }), 760)
})

test('resolveAssistantDockTopRpx keeps the dock under the finger on wider screens', () => {
  // 414px 宽：手指 y=420 → 顶部 380px → 380 * 750 / 414 ≈ 688rpx，渲染回去仍是 380px
  assert.equal(resolveAssistantDockTopRpx({ clientY: 420, windowWidth: 414, windowHeight: 896 }), 688)
})

test('buildAssistantDockStyle pins the dock to the selected side', () => {
  assert.deepEqual(buildAssistantDockStyle({ side: 'left', topRpx: 320 }), {
    top: '320rpx',
    left: '20rpx',
    right: 'auto',
  })

  assert.deepEqual(buildAssistantDockStyle({ side: 'right', topRpx: 412 }), {
    top: '412rpx',
    left: 'auto',
    right: '20rpx',
  })
})
