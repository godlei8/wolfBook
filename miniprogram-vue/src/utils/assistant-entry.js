import storage from '../services/storage'

/**
 * 打开规则速查（AI 助手）。原型里没有悬浮球，入口是首页搜索框的「问 AI」和各详情页的问题条。
 * question 会预填到输入框并直接发出去。
 */
export function openAssistant(scene = 'general', pageContext = {}, question = '') {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录后使用 AI 规则速查', icon: 'none' })
    uni.switchTab({ url: '/pages/user/index' })
    return
  }
  const context = encodeURIComponent(JSON.stringify(pageContext || {}))
  const ask = question ? `&question=${encodeURIComponent(question)}` : ''
  uni.navigateTo({ url: `/pages/assistant/chat?scene=${encodeURIComponent(scene)}&context=${context}${ask}` })
}
