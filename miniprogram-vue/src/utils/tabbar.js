// 自定义 tabBar（src/custom-tab-bar）的高亮由 tab 页在 onShow 里同步；H5 用原生 tabBar，没有 getTabBar，直接跳过
export function syncTabBar() {
  const pages = getCurrentPages()
  const page = pages[pages.length - 1]
  const tabBar = page && typeof page.getTabBar === 'function' ? page.getTabBar() : null
  if (tabBar && typeof tabBar.setRoute === 'function') {
    tabBar.setRoute(`/${page.route}`)
  }
}
