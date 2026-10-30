<script setup>
// 白天发言：发言顺序、当前发言人和这一位的倒计时（到点只提示，不强制换人）；发言者本人有「结束发言」。
// 房主的「跳过这位 / 发起投票」在房间页底部的操作栏。
import { computed } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { speechPanel } from '../../../utils/judge/day.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['act'])

const panel = computed(() => speechPanel(props.snapshot))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const speaker = computed(() => (props.snapshot.players || []).find((player) => player.seatNo === panel.value.speakerSeat) || null)
const overtime = computed(() => Boolean(props.snapshot.phaseDeadline) && clock.value.seconds === 0 && !props.snapshot.paused)

function endSpeech() {
  if (props.busy) return
  emit('act', { kind: 'advance', payload: { action: 'next_speaker' } })
}
</script>

<template>
  <view class="nt-gutter js">
    <view class="js-head">
      <text class="js-head__title serif">{{ title }}</text>
      <text class="js-head__clock" :class="{ 'js-head__clock--warn': clock.seconds <= 30 && clock.text !== '暂停' }">{{ clock.text }}</text>
    </view>

    <view class="nt-ink js-card">
      <view v-if="speaker" class="js-speaker">
        <view class="nt-token js-token">
          <image v-if="speaker.avatar" class="nt-token__img" :src="speaker.avatar" mode="aspectFill" />
          <view v-else class="nt-token__text js-token__text">{{ (speaker.nickname || '').slice(0, 1) }}</view>
        </view>
        <view class="js-speaker__text">
          <text class="js-speaker__label">正在发言</text>
          <text class="js-speaker__name serif">{{ speaker.seatNo }} 号 {{ speaker.nickname }}</text>
          <text v-if="overtime" class="js-speaker__over">时间到了，请尽快结束</text>
        </view>
      </view>
      <text v-else class="js-speaker__label">等待开始发言</text>
      <view v-if="panel.isSpeaker" class="nt-btn nt-btn--gold js-end" :class="{ 'nt-btn--disabled': busy }" @tap="endSpeech">结束发言</view>
    </view>

    <view class="nt-panel js-order">
      <text class="js-order__title">发言顺序</text>
      <view class="js-order__list">
        <text
          v-for="item in panel.sequence"
          :key="item.seatNo"
          class="js-order__seat"
          :class="`js-order__seat--${item.state}`"
        >{{ item.seatNo }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.js {
  padding-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.js-head {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
}

.js-head__title {
  flex: 1;
  font-size: 36rpx;
  font-weight: 900;
  color: var(--text);
}

.js-head__clock {
  font-size: 40rpx;
  font-weight: 900;
  color: var(--moon);
}

.js-head__clock--warn {
  color: var(--camp-wolf);
}

.js-card {
  padding: 32rpx;
  border-radius: 36rpx;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.js-speaker {
  display: flex;
  align-items: center;
  gap: 24rpx;
}

.js-token {
  width: 120rpx;
  height: 120rpx;
}

.js-token__text {
  font-size: 40rpx;
}

.js-speaker__text {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.js-speaker__label {
  color: var(--text-3);
  font-size: 24rpx;
}

.js-speaker__name {
  color: var(--text);
  font-size: 40rpx;
  font-weight: 900;
}

.js-speaker__over {
  color: var(--camp-wolf);
  font-size: 24rpx;
}

.js-order {
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.js-order__title {
  color: var(--text);
  font-size: 28rpx;
  font-weight: 700;
}

.js-order__list {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.js-order__seat {
  width: 64rpx;
  height: 64rpx;
  border-radius: 32rpx;
  background: var(--panel-raised);
  color: var(--text-2);
  font-size: 26rpx;
  font-weight: 700;
  line-height: 64rpx;
  text-align: center;
}

.js-order__seat--done {
  opacity: 0.4;
}

.js-order__seat--current {
  background: var(--gold);
  color: var(--ink);
}
</style>
