// 笔记的保存节奏（有单测）：每次改动先改画面，停手 0.8 秒后整份保存一次；离开页面时把没存的存掉；
// 没存上就留着，下一次再试。笔记页和法官房间共用。
export const SAVE_DELAY = 800

/**
 * save(note) 负责真正去存；onState 收到 'saving' / 'saved' / 'failed'；
 * setTimer / clearTimer 默认是 setTimeout / clearTimeout（测试里换掉）。
 */
export function createNoteSaver({ save, onState = () => {}, delay = SAVE_DELAY, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let latest = null
  let dirty = false
  let timer = null
  let chain = Promise.resolve()

  function run() {
    timer = null
    if (!dirty || !latest) return chain
    const note = latest
    dirty = false
    onState('saving')
    chain = chain.then(async () => {
      try {
        await save(note)
        if (!dirty) onState('saved')
      } catch (error) {
        // 没存上：这一份还算没存，除非之后又有更新的
        if (latest === note) dirty = true
        onState('failed')
      }
    })
    return chain
  }

  return {
    /** 笔记改了：记下最新的一份，重新计时。 */
    touch(note) {
      latest = note
      dirty = true
      if (timer !== null) clearTimer(timer)
      timer = setTimer(run, delay)
    },
    /** 马上把没存的存掉（离开页面、切后台时）。 */
    flush() {
      if (timer !== null) clearTimer(timer)
      return run()
    },
  }
}
