<script setup>
import { computed, reactive } from 'vue'
import storage from '../../services/storage'

const props = defineProps({
  scene: {
    type: String,
    default: 'general',
  },
  pageContext: {
    type: Object,
    default: () => ({}),
  },
})

const dockState = reactive({
  side: storage.getAssistantDockState().side || 'right',
  top: Number(storage.getAssistantDockState().top) || 420,
})

const gesture = reactive({
  startX: 0,
  startY: 0,
  dragging: false,
})

const dockStyle = computed(() => ({
  top: `${dockState.top}rpx`,
  left: dockState.side === 'left' ? '16rpx' : 'auto',
  right: dockState.side === 'right' ? '16rpx' : 'auto',
}))

function openAssistant() {
  if (!storage.getAuthToken()) {
    uni.showToast({ title: '请先登录后使用 AI 助手', icon: 'none' })
    uni.switchTab({ url: '/pages/user/index' })
    return
  }
  const context = encodeURIComponent(JSON.stringify(props.pageContext || {}))
  uni.navigateTo({
    url: `/pages/assistant/chat?scene=${encodeURIComponent(props.scene)}&context=${context}`,
  })
}

function handleTouchStart(event) {
  const touch = event.touches?.[0]
  if (!touch) return
  gesture.startX = touch.clientX
  gesture.startY = touch.clientY
  gesture.dragging = false
}

function handleTouchMove(event) {
  const touch = event.touches?.[0]
  if (!touch) return
  const systemInfo = uni.getSystemInfoSync()
  const deltaX = touch.clientX - gesture.startX
  const deltaY = touch.clientY - gesture.startY
  if (Math.abs(deltaX) > 6 || Math.abs(deltaY) > 6) {
    gesture.dragging = true
  }
  dockState.side = touch.clientX < systemInfo.windowWidth / 2 ? 'left' : 'right'
  const nextTopPx = Math.min(Math.max(touch.clientY - 40, 88), systemInfo.windowHeight - 140)
  dockState.top = Math.round((nextTopPx / systemInfo.windowWidth) * 750)
}

function handleTouchEnd() {
  storage.setAssistantDockState({ side: dockState.side, top: dockState.top })
  if (!gesture.dragging) {
    openAssistant()
  }
}
</script>

<template>
  <view
    class="assistant-dock"
    :style="dockStyle"
    @touchstart.stop="handleTouchStart"
    @touchmove.stop.prevent="handleTouchMove"
    @touchend.stop="handleTouchEnd"
  >
    <view class="wolf-ears">
      <view class="wolf-ear" />
      <view class="wolf-ear" />
    </view>
    <view class="wolf-face">
      <view class="wolf-eyes">
        <view class="wolf-eye" />
        <view class="wolf-eye" />
      </view>
      <view class="wolf-label">AI</view>
    </view>
    <view class="dock-text">狼顾问</view>
  </view>
</template>

<style scoped lang="scss">
.assistant-dock {
  position: fixed;
  z-index: 40;
  width: 120rpx;
  display: grid;
  justify-items: center;
  gap: 8rpx;
}

.wolf-ears {
  width: 84rpx;
  display: flex;
  justify-content: space-between;
  margin-bottom: -14rpx;
  z-index: 2;
}

.wolf-ear {
  width: 24rpx;
  height: 28rpx;
  border-radius: 8rpx 8rpx 2rpx 2rpx;
  background: linear-gradient(180deg, #ffc000, #5f4300);
  transform: skewY(-16deg);
}

.wolf-face {
  width: 96rpx;
  height: 96rpx;
  border-radius: 26rpx;
  background: linear-gradient(180deg, rgba(24, 24, 24, 0.98), rgba(6, 6, 6, 0.98));
  border: 2rpx solid rgba(255, 192, 0, 0.45);
  box-shadow: 0 16rpx 28rpx rgba(0, 0, 0, 0.28);
  display: grid;
  align-content: center;
  justify-items: center;
}

.wolf-eyes {
  width: 40rpx;
  display: flex;
  justify-content: space-between;
}

.wolf-eye {
  width: 8rpx;
  height: 12rpx;
  border-radius: 999rpx;
  background: #ffc000;
  box-shadow: 0 0 12rpx rgba(255, 192, 0, 0.35);
}

.wolf-label {
  margin-top: 8rpx;
  color: #ffffff;
  font-size: 20rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}

.dock-text {
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
  font-size: 20rpx;
  font-weight: 700;
}
</style>
