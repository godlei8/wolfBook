<script setup>
// 底部选择面板（夜镇样式）：一行一个选项，点一下就选中并收起。代替系统的白色滚轮 picker。
// 页面直接用这一层，里面不嵌别的自定义组件。选项是 { key, label, meta }，selected 是已选那一项的 key。
defineProps({
  title: { type: String, default: '' },
  options: { type: Array, default: () => [] },
  selected: { type: [String, Number], default: '' },
})
const emit = defineEmits(['pick', 'close'])
</script>

<template>
  <view class="ps">
    <view class="ps__scrim" @tap="emit('close')" />
    <view class="ps__panel">
      <text class="ps__title serif">{{ title }}</text>
      <scroll-view class="ps__list" scroll-y :show-scrollbar="false">
        <view
          v-for="item in options"
          :key="item.key"
          class="ps__option"
          :class="{ 'ps__option--on': item.key === selected }"
          @tap="emit('pick', item.key)"
        >
          <view class="ps__main">
            <text class="ps__name serif">{{ item.label }}</text>
            <text v-if="item.meta" class="ps__meta">{{ item.meta }}</text>
          </view>
          <text v-if="item.key === selected" class="ps__mark">已选</text>
          <text v-else class="nt-arrow">›</text>
        </view>
      </scroll-view>
      <view class="nt-btn nt-btn--ghost ps__cancel" @tap="emit('close')">取消</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.ps {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 80;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.ps__scrim {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: var(--scrim);
}

.ps__panel {
  position: relative;
  box-sizing: border-box;
  padding: 28rpx 32rpx calc(env(safe-area-inset-bottom) + 28rpx);
  background: var(--panel);
  border-top: 4rpx solid var(--ink);
  border-radius: 48rpx 48rpx 0 0;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.ps__title {
  font-size: 36rpx;
  font-weight: 900;
}

.ps__list {
  max-height: 60vh;
}

.ps__option {
  box-sizing: border-box;
  min-height: 112rpx;
  margin-bottom: 16rpx;
  padding: 20rpx 24rpx;
  border-radius: 28rpx;
  border: 3rpx solid var(--line);
  background: var(--panel-sunk);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.ps__option--on {
  border-color: var(--gold);
  background: var(--panel-raised);
}

.ps__main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.ps__name {
  font-size: 32rpx;
  font-weight: 900;
}

.ps__meta {
  font-size: 24rpx;
  color: var(--text-2);
}

.ps__mark {
  flex-shrink: 0;
  font-size: 24rpx;
  font-weight: 700;
  color: var(--gold);
}

.ps__cancel {
  flex-shrink: 0;
}
</style>
