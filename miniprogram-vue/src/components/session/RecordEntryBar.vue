<script setup>
defineProps({
  disabled: {
    type: Boolean,
    default: false,
  },
})

defineEmits(['open-scene'])

const entries = [
  { key: 'night', label: '夜间', accent: 'gold' },
  { key: 'speech', label: '发言', accent: 'default' },
  { key: 'vote', label: '投票', accent: 'gold' },
  { key: 'identity', label: '身份', accent: 'default' },
  { key: 'note', label: '备注', accent: 'default' },
]
</script>

<template>
  <view class="entry-shell glass-card">
    <view
      v-for="item in entries"
      :key="item.key"
      class="entry-item"
      :class="[`entry-item--${item.accent}`, { disabled }]"
      @tap="!disabled && $emit('open-scene', item.key)"
    >
      <view class="entry-label">{{ item.label }}</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.entry-shell {
  position: fixed;
  left: 24rpx;
  right: 24rpx;
  bottom: calc(env(safe-area-inset-bottom) + 24rpx);
  z-index: 18;
  padding: 18rpx;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12rpx;
}

.entry-item {
  min-width: 0;
  height: 82rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ececec;
}

.entry-item--gold {
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
}

.entry-item.disabled {
  opacity: 0.48;
}

.entry-label {
  font-size: 24rpx;
  font-weight: 700;
}
</style>
