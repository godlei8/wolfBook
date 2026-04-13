<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  player: {
    type: Object,
    default: null,
  },
})

defineEmits(['close', 'action'])

const actions = computed(() => {
  const player = props.player || {}
  return [
    player.isSheriff ? { key: 'remove_sheriff', label: '取消警长' } : { key: 'set_sheriff', label: '设为警长' },
    player.alive === false ? { key: 'revive', label: '恢复存活' } : { key: 'mark_dead', label: '标记出局' },
    { key: 'claim_role', label: '记录跳身份' },
    { key: 'note', label: '添加备注' },
    { key: 'wolf_pack', label: '加入狼坑' },
  ]
})
</script>

<template>
  <view v-if="visible" class="sheet-mask" @tap="$emit('close')">
    <view class="sheet-panel glass-card" @tap.stop>
      <view class="sheet-head">
        <view>
          <view class="section-title">{{ player?.seatNo }}号快捷操作</view>
          <view class="section-desc">{{ player?.claimedRole || '未跳身份' }} · {{ player?.note || '可直接打开对应结构化记录表单。' }}</view>
        </view>
        <view class="sheet-close" @tap="$emit('close')">关闭</view>
      </view>

      <view class="action-grid">
        <view v-for="item in actions" :key="item.key" class="action-card" @tap="$emit('action', item.key)">
          {{ item.label }}
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 31;
  background: rgba(0, 0, 0, 0.58);
  display: flex;
  align-items: flex-end;
}

.sheet-panel {
  width: 100%;
  padding: 28rpx 24rpx calc(env(safe-area-inset-bottom) + 26rpx);
  border-radius: 28rpx 28rpx 0 0;
}

.sheet-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18rpx;
}

.sheet-close {
  padding: 10rpx 18rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.06);
  color: #d8d8d8;
  font-size: 22rpx;
}

.action-grid {
  margin-top: 22rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}

.action-card {
  min-height: 88rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #f2f2f2;
  font-size: 24rpx;
  font-weight: 700;
}
</style>
