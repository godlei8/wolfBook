// 自定义导航栏的尺寸：状态栏高度 + 胶囊按钮那一行的高度（单位 px）。
// 小程序里按胶囊按钮的位置算，让返回键和胶囊上下对齐；H5 没有胶囊，按 44px 一行算。

let cached = null

export function getNavInsets() {
  if (cached) return cached
  let statusBar = 0
  let windowWidth = 375
  try {
    const info = typeof uni.getWindowInfo === 'function' ? uni.getWindowInfo() : uni.getSystemInfoSync()
    statusBar = info.statusBarHeight || 0
    windowWidth = info.windowWidth || windowWidth
  } catch (error) {
    statusBar = 0
  }

  let navBar = 44
  let capsuleSpace = 0
  // #ifdef MP
  try {
    const rect = uni.getMenuButtonBoundingClientRect()
    if (rect && rect.height) {
      navBar = (rect.top - statusBar) * 2 + rect.height
      capsuleSpace = windowWidth - rect.left
    }
  } catch (error) {
    navBar = 44
  }
  // #endif

  cached = { statusBar, navBar, top: statusBar + navBar, capsuleSpace, windowWidth }
  return cached
}

/** rpx 换算成 px（行内样式里需要和导航栏的 px 相加时用）。 */
export function rpxToPx(value) {
  return (value * getNavInsets().windowWidth) / 750
}

/** 返回上一页；没有上一页（分享卡片直接打开）时回到板子库。 */
export function goBackOrHome() {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: '/pages/boards/list' })
}
