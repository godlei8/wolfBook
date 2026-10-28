<script setup>
import NavBar from '../../components/NavBar.vue'
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
    confirmColor: hasLocalOnlyData ? '#E0403A' : '#F2B33D',
    success: (res) => {
      if (!res.confirm) return
      storage.clearAllLocalData()
      uni.showToast({ title: '已清理', icon: 'success' })
    },
  })
}
</script>

<template>
  <view class="nt-page st-page">
    <NavBar back title="设置" />

    <view class="nt-gutter nt-stack st-body">
      <view class="nt-panel">
        <text class="nt-label">关于</text>
        <text class="st-title serif">Wolfbook 夜镇卡牌</text>
        <text class="nt-meta">狼人杀板子图鉴、电子法官、对局笔记和酒馆交流的一体化小程序。</text>
        <text class="nt-meta">版本：V3 夜镇卡牌</text>
      </view>

      <view class="nt-row" @tap="clearData">
        <view class="st-row">
          <text class="st-row__title">清理本地数据</text>
          <text class="nt-meta">清除登录态、缓存和本地记录。未登录时本地笔记和收藏没有云端备份，清理前请先登录同步。</text>
        </view>
        <text class="nt-arrow">›</text>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.st-page {
  padding-bottom: calc(env(safe-area-inset-bottom) + 60rpx);
}

.st-body {
  margin-top: 16rpx;
}

.st-title {
  font-size: 40rpx;
  font-weight: 900;
}

.st-row {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  padding: 12rpx 0;
}

.st-row__title {
  font-size: 30rpx;
  font-weight: 700;
}
</style>
