// 法官房间页的轮询节奏和失败处理（纯函数，有单测）。
// 夜里 2 秒、白天 3 秒，所有人同一节奏（零场外：别让刷新快慢透露身份）；对局结束不再轮询。

export const NIGHT_POLL_MS = 2000
export const DAY_POLL_MS = 3000

export function pollInterval(snapshot) {
  if (snapshot?.roomStatus === 'finished') return 0
  if (snapshot?.roomStatus === 'playing' && snapshot?.currentPhase === 'night_action') return NIGHT_POLL_MS
  return DAY_POLL_MS
}

/**
 * result：成功 { ok: true, snapshot }，失败 { ok: false, code, message }。
 * 失败时保留上一次的快照，画面不白屏；4002（状态刚变、并发操作）不算错，下一次轮询自然拿到新状态。
 */
export function mergePollResult(previous, result, timing = {}) {
  // 提交之前发出、提交之后才回来的轮询带的是旧状态，丢掉，不然刚交的操作会「回退」一下
  if (result?.ok && timing.lastActionAt && timing.requestedAt < timing.lastActionAt) return { snapshot: previous, error: '' }
  if (result?.ok) return { snapshot: result.snapshot, error: '' }
  if (result?.code === 4002) return { snapshot: previous, error: '' }
  return { snapshot: previous, error: result?.message || '网络不太稳，正在重试' }
}
