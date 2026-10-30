<script setup>
// 对局记录：房间页右上角常驻入口打开的底部面板，白天夜里都能看（对局中只有公开信息，结束后带夜间行动）。
import { computed } from 'vue'
import { roundRows } from '../../../utils/judge/rounds.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
})
const emit = defineEmits(['close'])

const rows = computed(() => roundRows(props.snapshot))
</script>

<template>
  <view class="jl-mask" @tap="emit('close')">
    <view class="nt-panel jl-sheet" @tap.stop>
      <view class="jl-sheet__head">
        <text class="nt-h2">对局记录</text>
        <text class="jl-sheet__close" @tap="emit('close')">关闭</text>
      </view>
      <scroll-view scroll-y class="jl-sheet__body">
        <view v-if="!rows.length" class="jl-empty">还没有记录</view>
        <view v-for="row in rows" :key="row.dayNo" class="jl-day">
          <text class="jl-day__title serif">第 {{ row.dayNo }} 天</text>
          <text class="jl-line">夜里：{{ row.nightDeaths }}</text>
          <text v-for="(item, index) in row.nightActions" :key="`n${index}`" class="jl-line jl-line--night">{{ item }}</text>
          <text v-if="row.sheriff" class="jl-line">警长：{{ row.sheriff }}</text>
          <text v-if="row.speechOrder" class="jl-line">发言：{{ row.speechOrder }}</text>
          <view v-for="(exile, index) in row.exiles" :key="`e${index}`" class="jl-exile">
            <text class="jl-line">{{ index ? 'PK 投票' : '放逐投票' }}：{{ exile.out || exile.pk || '没有人出局' }}</text>
            <text v-for="(tally, tallyIndex) in exile.tallies" :key="tallyIndex" class="jl-line jl-line--sub">{{ tally }}</text>
            <text v-if="exile.abstain" class="jl-line jl-line--sub">{{ exile.abstain }}</text>
          </view>
          <text v-for="(skill, index) in row.skills" :key="`s${index}`" class="jl-line">{{ skill }}</text>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jl-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 70;
  background: rgba(7, 6, 10, 0.6);
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.jl-sheet {
  margin: 0 var(--page-gutter) calc(env(safe-area-inset-bottom) + 24rpx);
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.jl-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.jl-sheet__close {
  color: var(--moon);
  font-size: 26rpx;
}

.jl-sheet__body {
  max-height: 900rpx;
}

.jl-empty {
  padding: 48rpx 0;
  text-align: center;
  color: var(--text-2);
  font-size: 26rpx;
}

.jl-day {
  padding: 20rpx 0;
  border-top: 2rpx solid var(--panel-line);
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.jl-day__title {
  color: var(--gold);
  font-size: 30rpx;
  font-weight: 900;
}

.jl-exile {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jl-line {
  color: var(--text-soft);
  font-size: 24rpx;
  line-height: 1.6;
}

.jl-line--sub {
  padding-left: 24rpx;
  color: var(--text-2);
}

.jl-line--night {
  color: var(--text-3);
}
</style>
