// 微信小程序自定义 tabBar（原型 D：板子 / 笔记 / 开局 / 社区 / 我的，中间「开局」是凸起的金色按钮）。
// uni-app 会把 src/custom-tab-bar 原样拷进产物；H5 不支持，退回 pages.json 里的原生 tabBar。
Component({
  data: {
    selected: 0,
    list: [
      { pagePath: '/pages/boards/list', text: '板子', icon: '/static/tabbar/board-inactive.png', activeIcon: '/static/tabbar/board-active.png' },
      { pagePath: '/pages/sessions/index', text: '笔记', icon: '/static/tabbar/notes-inactive.png', activeIcon: '/static/tabbar/notes-active.png' },
      { pagePath: '/pages/judge/index', text: '开局', center: true },
      { pagePath: '/pages/community/index', text: '社区', icon: '/static/tabbar/community-inactive.png', activeIcon: '/static/tabbar/community-active.png' },
      { pagePath: '/pages/user/index', text: '我的', icon: '/static/tabbar/user-inactive.png', activeIcon: '/static/tabbar/user-active.png' },
    ],
  },
  methods: {
    // 每个 tab 页各有一份 tabBar 实例。组件自己取 getCurrentPages() 时新页面可能还没入栈，
    // 所以由 tab 页在 onShow 里调 utils/tabbar.js 的 syncTabBar() 告诉它当前路径
    setRoute(path) {
      const index = this.data.list.findIndex((item) => item.pagePath === path)
      if (index >= 0 && index !== this.data.selected) {
        this.setData({ selected: index })
      }
    },
    switchTab(event) {
      const item = this.data.list[event.currentTarget.dataset.index]
      if (!item) return
      wx.switchTab({ url: item.pagePath })
    },
  },
})
