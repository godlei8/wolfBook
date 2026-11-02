<script setup>
// 天亮公布死讯；有警长时警长本人选发言顺序（警左 / 警右，有人出局再加死左 / 死右）。
// 房主的「开始发言」在房间页底部的操作栏。
import { computed } from 'vue'
import { phaseTitle } from '../../../utils/judge/room-view.js'
import { announcePanel } from '../../../utils/judge/sheriff.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['act'])

const panel = computed(() => announcePanel(props.snapshot))
const title = computed(() => phaseTitle(props.snapshot))
const deadPlayers = computed(() => (props.snapshot.players || []).filter((player) => panel.value.deaths.includes(player.seatNo)))

function choose(option) {
  if (props.busy) return
  emit('act', { kind: 'sheriff', payload: { speechOrder: option.value } })
}
</script>

<template>
  <view class="nt-gutter ja">
    <text class="ja-title serif">{{ title }}</text>
    <view class="nt-ink ja-card">
      <text class="ja-card__sun">☀</text>
      <text class="ja-card__text serif">{{ panel.text }}</text>
      <view v-if="deadPlayers.length" class="ja-dead">
        <view v-for="player in deadPlayers" :key="player.seatNo" class="ja-dead__item">
          <view class="nt-token nt-token--dead ja-token">
            <image v-if="player.avatar" class="nt-token__img" :src="player.avatar" mode="aspectFill" />
            <view v-else class="nt-token__text">{{ (player.nickname || '').slice(0, 1) }}</view>
          </view>
          <text class="ja-dead__name">{{ player.seatNo }} 号 {{ player.nickname }}</text>
        </view>
      </view>
      <text v-if="panel.announcement" class="ja-card__note">{{ panel.announcement }}</text>
    </view>

    <view v-if="panel.sheriffSeat" class="nt-panel ja-order">
      <text class="ja-order__title">警长 {{ panel.sheriffSeat }} 号决定发言顺序</text>
      <view v-if="panel.canChooseOrder" class="ja-order__buttons">
        <view
          v-for="option in panel.orderOptions"
          :key="option.value"
          class="nt-btn nt-btn--ghost nt-btn--small ja-order__button"
          :class="{ 'nt-btn--disabled': busy }"
          @tap="choose(option)"
        >{{ option.label }}</view>
      </view>
      <text v-else class="ja-order__hint">等警长选择</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.ja {
  padding-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.ja-title {
  font-size: 36rpx;
  font-weight: 900;
  color: var(--text);
}

.ja-card {
  padding: 40rpx 32rpx;
  border-radius: 36rpx;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
}

.ja-card__sun {
  color: var(--gold);
  font-size: 72rpx;
}

.ja-card__text {
  color: var(--text);
  font-size: 40rpx;
  font-weight: 900;
}

.ja-card__note {
  color: var(--text-2);
  font-size: 24rpx;
  text-align: center;
}

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
  gap: 8rpx;
}

.ja-token {
  width: 96rpx;
  height: 96rpx;
}

.ja-dead__name {
  color: var(--text-soft);
  font-size: 22rpx;
}

.ja-order {
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.ja-order__title {
  color: var(--text);
  font-size: 30rpx;
  font-weight: 700;
}

.ja-order__buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.ja-order__button {
  flex: 1;
  min-width: 140rpx;
}

.ja-order__hint {
  color: var(--text-2);
  font-size: 24rpx;
}
</style>
