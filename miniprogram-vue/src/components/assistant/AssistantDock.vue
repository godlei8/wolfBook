<script setup>
import { computed, reactive } from 'vue'
import storage from '../../services/storage'
import { buildAssistantDockStyle, resolveAssistantDockTopRpx } from '../../utils/assistant-dock'

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

const dockStyle = computed(() => buildAssistantDockStyle({ side: dockState.side, topRpx: dockState.top }))

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
  dockState.top = resolveAssistantDockTopRpx({
    clientY: touch.clientY,
    windowWidth: systemInfo.windowWidth,
    windowHeight: systemInfo.windowHeight,
  })
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
  z-index: 24;
  width: 108rpx;
  display: grid;
  justify-items: center;
  gap: 10rpx;
}

.wolf-ears {
  width: 80rpx;
  display: flex;
  justify-content: space-between;
  margin-bottom: -12rpx;
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
  width: 88rpx;
  height: 88rpx;
  border-radius: 24rpx;
  background: linear-gradient(180deg, rgba(24, 24, 24, 0.98), rgba(6, 6, 6, 0.98));
  border: 2rpx solid rgba(255, 192, 0, 0.45);
  box-shadow: 0 14rpx 26rpx rgba(0, 0, 0, 0.28);
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
  min-height: 40rpx;
  padding: 6rpx 12rpx;
  border-radius: 999rpx;
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
  font-size: 20rpx;
  font-weight: 700;
  line-height: 1.2;
}
</style>
