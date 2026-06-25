export function shouldAutoScrollOnMessageAppend(previousCount = 0, nextCount = 0) {
  return Number(nextCount) > Number(previousCount)
}

export function createAssistantStreamBuffer(onFlush, options = {}) {
  const delayMs = Number(options.delayMs || 48)
  const timerApi = options.timerApi || globalThis

  let timerId = null
  let pending = ''

  function clearTimer() {
    if (timerId === null) {
      return
    }
    timerApi.clearTimeout?.(timerId)
    timerId = null
  }

  function flush() {
    clearTimer()
    if (!pending) {
      return
    }
    const delta = pending
    pending = ''
    onFlush?.(delta)
  }

  function schedule() {
    if (timerId !== null) {
      return
    }
    timerId = timerApi.setTimeout?.(() => {
      timerId = null
      flush()
    }, delayMs)
  }

  return {
    push(delta = '') {
      if (!delta) {
        return
      }
      pending += String(delta)
      schedule()
    },
    flush,
    dispose() {
      flush()
      clearTimer()
    },
  }
}
