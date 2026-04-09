<script setup>
import storage from '../../services/storage'

function clearData() {
  uni.showModal({
    title: '清理本地数据',
    content: '确认清理本地缓存、登录态和临时记录吗？云端收藏和云端笔记不会被删除。',
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
      <view class="section-desc">会清理登录缓存与本地临时数据，不会删除已经同步到云端的收藏和笔记。</view>
    </view>
  </view>
</template>
