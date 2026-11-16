<script setup>
// 底部弹出的规则面板：一组「标题 + 条目」，整屏滚动看。建房页和板子详情页的「通用规则」用它。
// 里面不嵌别的自定义组件（小程序里组件套组件会不渲染）。
defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  hint: { type: String, default: '' },
  sections: { type: Array, default: () => [] },
})
const emit = defineEmits(['close'])
</script>

<template>
  <view v-if="visible" class="rs" @touchmove.stop.prevent>
    <view class="rs__scrim" @tap="emit('close')" />
    <view class="rs__sheet">
      <view class="rs__handle" />
      <view class="rs__head">
        <view class="rs__titles">
          <text class="rs__title">{{ title }}</text>
          <text v-if="hint" class="rs__hint">{{ hint }}</text>
        </view>
        <text class="rs__close" @tap="emit('close')">关闭</text>
      </view>
      <scroll-view class="rs__body" scroll-y @touchmove.stop>
        <view v-for="section in sections" :key="section.title" class="rs__section">
          <text class="rs__section-title">{{ section.title }}</text>
          <view class="nt-bullets">
            <text v-for="item in section.items" :key="item" class="nt-bullet">{{ item }}</text>
          </view>
        </view>
        <view class="rs__end" />
      </scroll-view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.rs {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 90;
  display: flex;
  align-items: flex-end;
}

.rs__scrim {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: var(--scrim-strong);
}

.rs__sheet {
  position: relative;
  width: 100%;
  max-height: 82vh;
  box-sizing: border-box;
  padding: 16rpx 40rpx 0;
  border-radius: 40rpx 40rpx 0 0;
  background: var(--panel);
  border-top: 4rpx solid var(--ink);
  display: flex;
  flex-direction: column;
}

.rs__handle {
  align-self: center;
  width: 72rpx;
  height: 8rpx;
  border-radius: 4rpx;
  background: var(--line-strong);
}

.rs__head {
  padding: 24rpx 0 20rpx;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24rpx;
}

.rs__titles {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.rs__title {
  font-family: var(--serif);
  font-size: 40rpx;
  font-weight: 900;
  color: var(--text);
}

.rs__hint {
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--text-2);
}

.rs__close {
  flex-shrink: 0;
  height: 88rpx;
  padding: 0 28rpx;
  margin-right: -28rpx;
  display: flex;
  align-items: center;
  font-size: 28rpx;
  color: var(--moon);
}

.rs__body {
  flex: 1;
  min-height: 0;
  height: 62vh;
}

.rs__section {
  padding: 24rpx 0;
  border-top: 2rpx solid var(--panel-line);
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.rs__section-title {
  font-family: var(--serif);
  font-size: 32rpx;
  font-weight: 900;
  color: var(--gold);
}

.rs__end {
  height: calc(env(safe-area-inset-bottom) + 40rpx);
}
</style>
