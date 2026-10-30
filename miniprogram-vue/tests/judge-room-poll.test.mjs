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
