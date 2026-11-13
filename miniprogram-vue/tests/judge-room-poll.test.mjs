import test from 'node:test'
import assert from 'node:assert/strict'
import { mergePollResult, pollInterval } from '../src/utils/judge/room-poll.js'

test('nightPollsFasterThanDay', () => {
  assert.equal(pollInterval({ roomStatus: 'playing', currentPhase: 'night_action' }), 2000)
  assert.equal(pollInterval({ roomStatus: 'playing', currentPhase: 'day_speech' }), 3000)
  assert.equal(pollInterval({ roomStatus: 'lobby', currentPhase: 'lobby' }), 3000)
  assert.equal(pollInterval({ roomStatus: 'finished', currentPhase: 'day_speech' }), 0)
  assert.equal(pollInterval(null), 3000)
})

test('pollFailureKeepsTheLastSnapshot', () => {
  const previous = { roomId: 'A' }
  const next = { roomId: 'B' }
  assert.deepEqual(mergePollResult(previous, { ok: true, snapshot: next }), { snapshot: next, error: '' })
  const busy = mergePollResult(previous, { ok: false, code: 4002, message: '房间状态已变化' })
  assert.equal(busy.snapshot, previous)
  assert.equal(busy.error, '')
  const down = mergePollResult(previous, { ok: false, code: 5000, message: '服务器开小差' })
  assert.equal(down.snapshot, previous)
  assert.equal(down.error, '服务器开小差')
})

test('aPollSentBeforeAnActionCannotOverwriteIt', () => {
  const afterAction = { roomId: 'after' }
  const stalePoll = { ok: true, snapshot: { roomId: 'before' } }
  assert.equal(mergePollResult(afterAction, stalePoll, { requestedAt: 100, lastActionAt: 200 }).snapshot, afterAction)
  assert.equal(mergePollResult(afterAction, stalePoll, { requestedAt: 300, lastActionAt: 200 }).snapshot, stalePoll.snapshot)
  assert.equal(mergePollResult(afterAction, stalePoll).snapshot, stalePoll.snapshot)
})

test('aMissingRoomStopsPolling', () => {
  const merged = mergePollResult(null, { ok: false, code: 4004, message: '房间不存在' })
  assert.equal(merged.gone, true)
  assert.equal(merged.error, '房间不存在')
  assert.ok(!mergePollResult(null, { ok: false, code: 5000, message: '网络错误' }).gone, '别的错误照常重试')
  assert.ok(!mergePollResult(null, { ok: true, snapshot: { roomId: 1 } }).gone)
})

test('aDissolvedRoomOrBeingRemovedSendsYouBack', () => {
  const inRoom = { roomId: 'r1', roomStatus: 'lobby' }
  const dissolved = mergePollResult(inRoom, { ok: false, code: 4004, message: '房间不存在' })
  assert.equal(dissolved.gone, true)
  assert.equal(dissolved.exit, '房主已解散房间', '看着房间时房间没了 = 被解散了')
  assert.equal(mergePollResult(null, { ok: false, code: 4004, message: '房间不存在' }).exit, undefined, '一开始就打不开的是房号不对，不弹回')
  const removed = mergePollResult(inRoom, { ok: false, code: 4003, message: '你已被房主请离这个房间' })
  assert.equal(removed.gone, true)
  assert.equal(removed.exit, '你已被房主请离这个房间')
  assert.equal(mergePollResult(null, { ok: false, code: 4003, message: '你已被房主请离这个房间' }).exit, '你已被房主请离这个房间')
})
