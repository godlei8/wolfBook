<script setup>
import { computed } from 'vue'
import { getPhaseLabel, getStatusLabel } from '../../utils/session/constants'
import { formatDateTime } from '../../utils/format'

const props = defineProps({
  session: {
    type: Object,
    default: null,
  },
})

defineEmits(['edit-base', 'finish-session'])

const phaseLabel = computed(() => getPhaseLabel(props.session?.currentPhase))
const statusLabel = computed(() => getStatusLabel(props.session?.status))
</script>

<template>
  <view class="glass-card section-card">
    <view class="header-top">
      <view>
        <view class="hero-title header-title">{{ session?.boardName || '未命名对局' }}</view>
        <view class="hero-subtitle">{{ session?.playerCount || 0 }} 人局 · 最近更新 {{ formatDateTime(session?.updateTime) }}</view>
      </view>
      <view class="header-actions">
        <button class="button-ghost header-action" @tap="$emit('edit-base')">编辑</button>
        <button class="button-primary header-action" @tap="$emit('finish-session')">结算</button>
      </view>
    </view>

    <view class="header-pills">
      <view class="pill pill-gold">{{ statusLabel }}</view>
      <view class="pill pill-white">第 {{ session?.currentDay || 1 }} 天</view>
      <view class="pill pill-white">{{ phaseLabel }}</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.header-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
}

.header-title {
  font-size: 44rpx;
}

.header-actions {
  display: flex;
  gap: 12rpx;
  flex-shrink: 0;
}

.header-action {
  margin: 0;
  min-width: 98rpx;
  height: 64rpx;
  line-height: 64rpx;
  padding: 0 18rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
}

.header-pills {
  margin-top: 18rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
</style>
