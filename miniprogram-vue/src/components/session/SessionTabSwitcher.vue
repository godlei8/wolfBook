<script setup>
const props = defineProps({
  activeTab: {
    type: String,
    default: 'overview',
  },
})

defineEmits(['change'])

const tabs = [
  { key: 'overview', label: '总览' },
  { key: 'seat', label: '座位' },
  { key: 'timeline', label: '时间线' },
  { key: 'review', label: '复盘' },
]
</script>

<template>
  <view class="glass-card tab-shell">
    <view
      v-for="item in tabs"
      :key="item.key"
      class="tab-item"
      :class="{ active: activeTab === item.key }"
      @tap="$emit('change', item.key)"
    >
      {{ item.label }}
    </view>
  </view>
</template>

<style scoped lang="scss">
.tab-shell {
  margin-top: 24rpx;
  padding: 12rpx;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10rpx;
}

.tab-item {
  min-width: 0;
  height: 76rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #a8a8a8;
  font-size: 24rpx;
  font-weight: 700;
}

.tab-item.active {
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
}
</style>
