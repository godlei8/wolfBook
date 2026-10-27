<script setup>
// 头像筹码（原型「头像筹码」）：主题色描边 + 黑外圈 + 硬投影，右下角标是张数，出局时变灰虚线。
import { computed } from 'vue'

const props = defineProps({
  image: {
    type: String,
    default: '',
  },
  accent: {
    type: String,
    default: '#F4EAD5',
  },
  // 直径，单位 rpx
  size: {
    type: Number,
    default: 68,
  },
  count: {
    type: Number,
    default: 0,
  },
  dead: {
    type: Boolean,
    default: false,
  },
})

const boxStyle = computed(() => ({ width: `${props.size}rpx`, height: `${props.size}rpx` }))
const imageStyle = computed(() => ({
  borderColor: props.dead ? '#6B6280' : props.accent,
  borderWidth: `${props.size >= 96 ? 6 : 5}rpx`,
}))
</script>

<template>
  <view class="role-token" :class="{ 'role-token--dead': dead }" :style="boxStyle">
    <image class="role-token-image" :src="image" mode="aspectFill" :style="imageStyle" webp />
    <text v-if="count > 1" class="role-token-count">×{{ count }}</text>
  </view>
</template>

<style scoped lang="scss">
.role-token {
  position: relative;
  flex-shrink: 0;
}

.role-token-image {
  width: 100%;
  height: 100%;
  display: block;
  border-radius: 50%;
  border-style: solid;
  box-sizing: border-box;
  background: var(--ink);
  box-shadow:
    0 0 0 4rpx var(--ink),
    4rpx 6rpx 0 4rpx var(--ink);
}

.role-token--dead .role-token-image {
  border-style: dashed;
  filter: grayscale(1) brightness(0.55);
}

.role-token-count {
  position: absolute;
  right: -16rpx;
  bottom: -12rpx;
  padding: 0 9rpx;
  border-radius: 16rpx;
  border: 3rpx solid var(--ink);
  background: var(--text);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 700;
  line-height: 28rpx;
}
</style>
