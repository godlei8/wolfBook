<script setup>
import storage from '../../services/storage'

function clearData() {
  const loggedIn = !!storage.getAuthToken()
  const localSessions = storage.getSessions().length
  const localFavorites = storage.getFavorites().length
  const hasLocalOnlyData = !loggedIn && (localSessions > 0 || localFavorites > 0)

  // 未登录时笔记和收藏只存在本地，清理就是永久删除，必须说清楚
  const content = hasLocalOnlyData
    ? `你当前未登录，本地的 ${localSessions} 条对局笔记和 ${localFavorites} 个收藏没有云端备份，清理后将永久删除。建议先登录同步再清理。`
    : '将清除登录态、缓存和本地记录。已同步到云端的收藏和笔记会在重新登录后恢复。'

  uni.showModal({
    title: hasLocalOnlyData ? '确认永久删除本地数据？' : '清理本地数据',
    content,
    confirmText: hasLocalOnlyData ? '仍要删除' : '清理',
    confirmColor: hasLocalOnlyData ? '#ff4d4f' : '#ffc000',
    success: (res) => {
      if (!res.confirm) return
      storage.clearAllLocalData()
      uni.showToast({ title: '已清理', icon: 'success' })
    },
  })
}
</script>

<template>
  <view class="page-shell">
    <view class="hero-title">设置</view>
    <view class="hero-subtitle">清理本地缓存并查看当前版本说明。</view>

    <view class="glass-card section-card">
      <view class="section-title">关于项目</view>
      <view class="section-desc">狼人杀图鉴、对局笔记与社区交流的一体化小程序。</view>
      <view class="section-meta">版本：V2 开发版</view>
    </view>

    <view class="glass-card section-card" @tap="clearData">
      <view class="section-title">清理本地数据</view>
      <view class="section-desc">会清除登录态、缓存和本地记录。未登录时本地笔记和收藏没有云端备份，清理前请先登录同步。</view>
    </view>
  </view>
</template>
