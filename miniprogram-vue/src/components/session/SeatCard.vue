<script setup>
const props = defineProps({
  player: {
    type: Object,
    default: null,
  },
})

defineEmits(['click'])
</script>

<template>
  <view class="seat-card" :class="{ 'seat-card--dead': player?.alive === false }" @tap="$emit('click', player)">
    <view class="seat-top">
      <view class="seat-title">
        <view class="seat-no">{{ player?.seatNo }}号</view>
        <view v-if="player?.nickname" class="seat-nickname">{{ player.nickname }}</view>
      </view>
      <view v-if="player?.isSheriff" class="seat-badge">警长</view>
    </view>
    <view class="seat-meta-row">
      <view class="seat-meta">{{ player?.alive === false ? '已出局' : '存活中' }}</view>
      <view class="seat-suspicion">
        <view
          v-for="index in 3"
          :key="index"
          class="suspicion-dot"
          :class="{ active: index <= (player?.suspicionLevel || 0) }"
        />
      </view>
    </view>
    <view class="seat-role">{{ player?.claimedRole || '未跳身份' }}</view>
    <view v-if="player?.tags?.length" class="seat-tags">
      <view v-for="tag in player.tags.slice(0, 3)" :key="tag" class="seat-tag">{{ tag }}</view>
    </view>
    <view class="seat-note">{{ player?.note || '点击可快速记录身份、出局或备注。' }}</view>
  </view>
</template>

<style scoped lang="scss">
.seat-card {
  min-height: 220rpx;
  padding: 20rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.04);
  border: 1rpx solid rgba(255, 255, 255, 0.04);
  box-sizing: border-box;
}

.seat-card--dead {
  opacity: 0.72;
}

.seat-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12rpx;
}

.seat-title {
  min-width: 0;
}

.seat-no {
  font-size: 30rpx;
  font-weight: 700;
}

.seat-nickname {
  margin-top: 6rpx;
  color: #8f8f8f;
  font-size: 22rpx;
}

.seat-badge {
  padding: 8rpx 14rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
  font-size: 20rpx;
}

.seat-meta-row {
  margin-top: 10rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.seat-meta {
  color: #9f9f9f;
  font-size: 22rpx;
}

.seat-suspicion {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.suspicion-dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.14);
}

.suspicion-dot.active {
  background: #ffc000;
}

.seat-role {
  margin-top: 12rpx;
  color: #ffffff;
  font-size: 26rpx;
  font-weight: 700;
}

.seat-tags {
  margin-top: 12rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
}

.seat-tag {
  padding: 6rpx 12rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.12);
  color: #f4cf70;
  font-size: 20rpx;
}

.seat-note {
  margin-top: 14rpx;
  color: #b0b0b0;
  font-size: 22rpx;
  line-height: 1.6;
  min-height: 70rpx;
}
</style>
