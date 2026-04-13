<script setup>
const props = defineProps({
  session: {
    type: Object,
    default: null,
  },
})

defineEmits(['jump-seat', 'open-wolfpack-history'])
</script>

<template>
  <view class="glass-card section-card">
    <view class="section-title">战况摘要</view>
    <view class="summary-grid">
      <view class="summary-card">
        <view class="summary-label">存活人数</view>
        <view class="summary-value">{{ session?.summary?.aliveCount ?? session?.playerCount ?? 0 }}</view>
      </view>
      <view class="summary-card">
        <view class="summary-label">出局人数</view>
        <view class="summary-value">{{ session?.summary?.deadCount ?? 0 }}</view>
      </view>
      <view class="summary-card tappable" @tap="$emit('jump-seat', session?.sheriffSeat)">
        <view class="summary-label">警长</view>
        <view class="summary-value">{{ session?.sheriffSeat ? `${session.sheriffSeat}号` : '未设置' }}</view>
      </view>
      <view class="summary-card">
        <view class="summary-label">今日放逐</view>
        <view class="summary-value">{{ session?.summary?.todayOutSeat ? `${session.summary.todayOutSeat}号` : '暂无' }}</view>
      </view>
    </view>
    <view class="wolf-pack tappable" @tap="$emit('open-wolfpack-history')">
      <view class="summary-label">最新狼坑</view>
      <view class="wolf-pack-text">{{ session?.summary?.latestWolfPackText || '暂无狼坑记录' }}</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.summary-grid {
  margin-top: 18rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}

.summary-card {
  min-width: 0;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
}

.tappable {
  border: 1rpx solid rgba(255, 192, 0, 0.1);
}

.summary-label {
  color: #8f8f8f;
  font-size: 22rpx;
}

.summary-value {
  margin-top: 10rpx;
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.2;
}

.wolf-pack {
  margin-top: 18rpx;
  padding: 18rpx 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 192, 0, 0.08);
}

.wolf-pack-text {
  margin-top: 10rpx;
  color: #f4cf70;
  font-size: 26rpx;
  line-height: 1.6;
}
</style>
