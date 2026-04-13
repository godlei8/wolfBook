<script setup>
import SeatCard from './SeatCard.vue'

defineProps({
  players: {
    type: Array,
    default: () => [],
  },
})

defineEmits(['seat-click'])
</script>

<template>
  <view class="glass-card section-card">
    <view class="section-title">座位面板</view>
    <view class="section-desc">以座位为核心查看身份状态、警长与备注，点击座位可快速记录对应事件。</view>
    <view class="seat-grid">
      <SeatCard
        v-for="player in players"
        :key="player.seatNo"
        :player="player"
        @click="$emit('seat-click', $event)"
      />
    </view>
  </view>
</template>

<style scoped lang="scss">
.seat-grid {
  margin-top: 18rpx;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14rpx;
}
</style>
