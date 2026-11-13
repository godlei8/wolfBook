<script setup>
// 天亮公布死讯；有警长时警长本人选发言顺序（警左 / 警右，有人出局再加死左 / 死右）。
// 停几秒后系统自动开始发言（警上自爆那天自动天黑）；法官的「开始发言」在房间页底部的操作栏。
import { computed } from 'vue'
import { phaseTitle } from '../../../utils/judge/room-view.js'
import { announcePanel } from '../../../utils/judge/sheriff.js'
import { gameAreaStyle, stageLayout } from '../../../utils/judge/stage.js'
import { useStageFit } from '../../../utils/judge/stage-fit.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  busy: { type: Boolean, default: false },
  now: { type: Number, default: () => Date.now() },
  // 房间页记住的牌桌高度（rpx）和底部有没有固定操作栏
  stageHeight: { type: Number, default: 0 },
  bar: { type: Boolean, default: false },
})
const emit = defineEmits(['act', 'fit'])

const layout = computed(() => stageLayout(props.snapshot, 'announce', { stageHeight: props.stageHeight }))
const panel = computed(() => announcePanel(props.snapshot, props.now))
const title = computed(() => phaseTitle(props.snapshot))
const deadPlayers = computed(() => (props.snapshot.players || []).filter((player) => panel.value.deaths.includes(player.seatNo)))
useStageFit(props, emit, () => layout.value.cardHeight)

function seatClass(seat) {
  return { 'nt-seat--dim': seat.dim, 'nt-seat--on': seat.selected, 'nt-seat--speaking': seat.speaking, 'nt-seat--out': seat.out }
}

function tapSeat() {}
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
          <text class="nt-scene__icon">☀</text>
          <text class="nt-scene__title">{{ panel.text }}</text>
          <view v-if="deadPlayers.length" class="ja-dead">
            <view v-for="player in deadPlayers" :key="player.seatNo" class="ja-dead__item">
              <view class="nt-token nt-token--dead ja-token">
                <image v-if="player.avatar" class="nt-token__img" :src="player.avatar" mode="aspectFill" />
                <view v-else class="nt-token__text">{{ (player.nickname || '').slice(0, 1) }}</view>
              </view>
              <text class="ja-dead__name">{{ player.seatNo }} 号</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <view class="nt-game__head">
      <text class="nt-game__title serif">{{ title }}</text>
    </view>

    <view class="nt-gutter nt-game__area" :style="gameAreaStyle(bar)">
      <view class="nt-panel nt-game__panel">
        <text class="nt-game__lead">{{ panel.sheriffSeat ? `天亮了 · 警长 ${panel.sheriffSeat} 号` : '天亮了' }}</text>
        <text v-if="panel.announcement" class="nt-game__hint">{{ panel.announcement }}</text>
        <text class="nt-game__hint ja-next">{{ panel.hint }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.ja-dead {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 24rpx;
}

.ja-dead__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
}

.ja-token {
  width: 96rpx;
  height: 96rpx;
}

.ja-dead__name {
  font-size: 24rpx;
  color: var(--text-2);
}

.ja-next {
  color: var(--moon);
}
</style>
