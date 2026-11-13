<script setup>
// 白天发言：中间是当前发言人，两侧座位亮出发言的人；下面只提示警左 / 警右（不列整个顺序）和这一位的倒计时（到点只提示，不强制换人），
// 发言者本人有「结束发言」。房主的「跳过这位 / 发起投票」在房间页底部的操作栏。
import { computed } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { speechPanel } from '../../../utils/judge/day.js'
import { gameAreaStyle, stageLayout } from '../../../utils/judge/stage.js'
import { useStageFit } from '../../../utils/judge/stage-fit.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
  // 房间页记住的牌桌高度（rpx）和底部有没有固定操作栏
  stageHeight: { type: Number, default: 0 },
  bar: { type: Boolean, default: false },
})
const emit = defineEmits(['act', 'fit'])

const layout = computed(() => stageLayout(props.snapshot, 'speech', { stageHeight: props.stageHeight }))
const panel = computed(() => speechPanel(props.snapshot))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const speaker = computed(() => (props.snapshot.players || []).find((player) => player.seatNo && player.seatNo === panel.value.speakerSeat) || null)
const overtime = computed(() => Boolean(props.snapshot.phaseDeadline) && clock.value.seconds === 0 && !props.snapshot.paused)
const nextSeat = computed(() => panel.value.nextSeat)
useStageFit(props, emit, () => layout.value.cardHeight)

function seatClass(seat) {
  return { 'nt-seat--dim': seat.dim, 'nt-seat--on': seat.selected, 'nt-seat--speaking': seat.speaking, 'nt-seat--out': seat.out }
}

function tapSeat() {}

function endSpeech() {
  if (props.busy) return
  emit('act', { kind: 'advance', payload: { action: 'next_speaker' } })
}
</script>

<template>
  <view class="nt-game">
    <view class="nt-stage">
      <view v-for="side in layout.sides" :key="side.key" class="nt-stage__col" :class="`nt-stage__col--${side.key}`" :style="{ gap: `${layout.gap}rpx` }">
        <view
          v-for="seat in side.seats"
          :key="seat.seatNo"
          class="nt-seat"
          :class="seatClass(seat)"
          :style="{ width: `${layout.size}rpx`, height: `${layout.size}rpx` }"
          @tap="tapSeat(seat)"
        >
          <view class="nt-token nt-seat__token" :class="{ 'nt-token--dead': seat.dead }">
            <image v-if="seat.avatar" class="nt-token__img" :src="seat.avatar" mode="aspectFill" />
            <view v-else class="nt-token__text nt-seat__text">{{ seat.initial }}</view>
          </view>
          <text class="nt-seat__no">{{ seat.seatNo }}</text>
          <text v-if="seat.role" class="nt-seat__role" :style="{ backgroundColor: seat.roleColor }">{{ seat.role }}</text>
          <text v-if="seat.tag" class="nt-seat__tag" :class="seat.tagKind ? `nt-seat__tag--${seat.tagKind}` : ''">{{ seat.tag }}</text>
          <text v-if="seat.sheriff" class="nt-seat__sheriff">★</text>
        </view>
      </view>

      <view class="nt-stage__center nt-card" :style="{ height: `${layout.cardHeight}rpx` }">
        <image class="nt-scene__bg" src="/static/scenes/square.webp" mode="aspectFill" webp />
        <view class="nt-scene__body">
          <block v-if="speaker">
            <text class="nt-scene__kicker">正在发言</text>
            <view class="nt-token nt-scene__avatar">
              <image v-if="speaker.avatar" class="nt-token__img" :src="speaker.avatar" mode="aspectFill" />
              <view v-else class="nt-token__text nt-scene__avatar-text">{{ (speaker.nickname || '').slice(0, 1) }}</view>
            </view>
            <text class="nt-scene__title">{{ speaker.seatNo }} 号</text>
            <text class="nt-scene__text">{{ speaker.nickname }}</text>
            <text v-if="overtime" class="js-over">时间到了，请尽快结束</text>
            <text v-else-if="nextSeat" class="nt-scene__text">下一位 {{ nextSeat }} 号</text>
          </block>
          <block v-else>
            <text class="nt-scene__icon">☀</text>
            <text class="nt-scene__title">等待开始发言</text>
          </block>
        </view>
      </view>
    </view>

    <view class="nt-game__head">
      <text class="nt-game__title serif">{{ title }}</text>
      <text class="nt-game__clock" :class="{ 'nt-game__clock--warn': clock.seconds <= panel.warnAt && clock.text !== '暂停' }">{{ clock.text }}</text>
    </view>

    <view class="nt-gutter nt-game__area" :style="gameAreaStyle(bar)">
      <view class="nt-panel nt-game__panel">
        <view v-if="panel.directionText" class="js-direction">
          <text class="nt-game__label">发言</text>
          <text class="js-direction__text serif">{{ panel.directionText }}</text>
        </view>
        <view v-if="panel.isSpeaker" class="nt-game__buttons">
          <view class="nt-btn nt-btn--gold nt-game__button" :class="{ 'nt-btn--disabled': busy }" @tap="endSpeech">结束发言</view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.js-over {
  font-size: 26rpx;
  font-weight: 700;
  color: var(--camp-wolf);
}

.js-direction {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
}

.js-direction__text {
  font-size: 44rpx;
  font-weight: 700;
  color: var(--gold);
}
</style>
