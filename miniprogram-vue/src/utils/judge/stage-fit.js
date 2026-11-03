// 牌桌贴底：视图挂上后、每次快照变化时量一下自己（根节点 .nt-game）的底边，
// 把算出的牌桌高度 emit('fit') 给房间页；房间页记住它，下一个阶段的视图一挂上就是这个高度，不会先小后大。
import { getCurrentInstance, nextTick, onMounted, watch } from 'vue'
import { fitStageHeight } from './stage.js'

export function useStageFit(props, emit, cardHeight) {
  const instance = getCurrentInstance()

  function fit() {
    nextTick(() => {
      let info = null
      try {
        info = typeof uni.getWindowInfo === 'function' ? uni.getWindowInfo() : uni.getSystemInfoSync()
      } catch (error) {
        return
      }
      uni.createSelectorQuery().in(instance.proxy).select('.nt-game').boundingClientRect((rect) => {
        if (!rect) return
        const next = fitStageHeight({
          cardHeight: cardHeight(),
          current: props.stageHeight,
          bottom: rect.bottom,
          windowHeight: info?.windowHeight,
          windowWidth: info?.windowWidth,
          bar: props.bar,
          safeBottom: info?.safeAreaInsets?.bottom || 0,
        })
        if (next !== null) emit('fit', next)
      }).exec()
    })
  }

  onMounted(fit)
  watch(() => [props.snapshot, props.bar], fit)
}
