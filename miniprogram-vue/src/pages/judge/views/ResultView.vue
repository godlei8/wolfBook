<script setup>
// 对局结束：胜负和判定依据、每个人的身份和存活，再来一局 / 看对局记录。
import { computed } from 'vue'
import { verdictText } from '../../../utils/judge/rounds.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  board: { type: Object, default: null },
})
const emit = defineEmits(['open-log'])

const verdict = computed(() => verdictText(props.snapshot))
const players = computed(() => (props.snapshot.players || []).filter((player) => player.seatNo).sort((a, b) => a.seatNo - b.seatNo))

function playAgain() {
  if (!props.snapshot.boardId) return
  uni.redirectTo({ url: `/pages/judge/create?boardId=${props.snapshot.boardId}` })
}
</script>

<template>
  <view class="nt-gutter jx">
    <view class="nt-ink jx-hero">
      <image class="jx-hero__bg" :src="board && board.backgroundImage ? board.backgroundImage : '/static/scenes/square.webp'" mode="aspectFill" webp />
      <view class="jx-hero__plate">
        <text class="jx-hero__title serif">{{ verdict.title }}</text>
        <text class="jx-hero__reason">{{ verdict.reason }}</text>
      </view>
    </view>

    <view class="nt-panel jx-list">
      <view v-for="player in players" :key="player.seatNo" class="jx-row" :class="{ 'jx-row--dead': player.alive === false }">
        <text class="jx-row__no serif">{{ player.seatNo }}</text>
        <text class="jx-row__name">{{ player.nickname }}</text>
        <text class="jx-row__role" :class="{ 'jx-row__role--wolf': player.faction === '狼人' }">{{ player.roleName || '—' }}</text>
        <text class="jx-row__state">{{ player.alive === false ? '出局' : '存活' }}</text>
      </view>
    </view>

    <view class="jx-buttons">
      <view class="nt-btn nt-btn--ghost jx-button" @tap="emit('open-log')">对局记录</view>
      <view class="nt-btn nt-btn--gold jx-button" @tap="playAgain">再来一局</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jx {
  padding-top: 24rpx;
  padding-bottom: 48rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jx-hero {
  position: relative;
  height: 360rpx;
  border-radius: 40rpx;
  overflow: hidden;
  background: #1b1724;
}

.jx-hero__bg {
  width: 100%;
  height: 100%;
}

.jx-hero__plate {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 24rpx 32rpx;
  background: var(--ink);
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.jx-hero__title {
  color: var(--gold);
  font-size: 44rpx;
  font-weight: 900;
}

.jx-hero__reason {
  color: var(--text-soft);
  font-size: 24rpx;
}

.jx-list {
  padding: 12rpx 28rpx;
}

.jx-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  min-height: 76rpx;
  border-bottom: 2rpx solid var(--panel-line);
}

.jx-row--dead {
  opacity: 0.55;
}

.jx-row__no {
  width: 48rpx;
  color: var(--gold);
  font-size: 30rpx;
  font-weight: 900;
}

.jx-row__name {
  flex: 1;
  min-width: 0;
  color: var(--text);
  font-size: 26rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jx-row__role {
  color: var(--camp-god);
  font-size: 26rpx;
  font-weight: 700;
}

.jx-row__role--wolf {
  color: var(--camp-wolf);
}

.jx-row__state {
  width: 72rpx;
  text-align: right;
  color: var(--text-3);
  font-size: 22rpx;
}

.jx-buttons {
  display: flex;
  gap: 16rpx;
}

.jx-button {
  flex: 1;
}
</style>
