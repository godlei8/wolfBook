<script setup>
// 对局结束：胜负和判定依据、每个人的身份卡（胜方金边、出局变暗），底部固定「对局记录 / 再来一局」。
import { computed } from 'vue'
import { resultCards, verdictText } from '../../../utils/judge/rounds.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  board: { type: Object, default: null },
})
const emit = defineEmits(['open-log'])

const verdict = computed(() => verdictText(props.snapshot))
const cards = computed(() => resultCards(props.snapshot, props.board))

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

    <view class="jx-grid">
      <view
        v-for="card in cards"
        :key="card.seatNo"
        class="nt-panel jx-card"
        :class="{ 'jx-card--win': card.winner, 'jx-card--dead': !card.alive }"
      >
        <view class="nt-token jx-token" :class="{ 'nt-token--dead': !card.alive }">
          <image v-if="card.portrait" class="nt-token__img" :src="card.portrait" mode="aspectFill" />
          <view v-else class="nt-token__text jx-token__text">{{ card.roleName.slice(0, 1) }}</view>
        </view>
        <view class="jx-card__text">
          <view class="jx-card__top">
            <text class="jx-card__no serif">{{ card.seatNo }}</text>
            <text class="jx-card__role" :class="`jx-card__role--${card.camp}`">{{ card.roleName }}</text>
          </view>
          <text class="jx-card__name">{{ card.nickname }}</text>
        </view>
        <text class="jx-card__state">{{ card.alive ? '存活' : '出局' }}</text>
      </view>
    </view>

    <view class="nt-bar">
      <view class="nt-btn nt-btn--ghost jx-button" @tap="emit('open-log')">对局记录</view>
      <view class="nt-btn nt-btn--gold jx-button" @tap="playAgain">再来一局</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jx {
  padding-top: 24rpx;
  padding-bottom: var(--bar-space);
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jx-hero {
  position: relative;
  height: 320rpx;
  border-radius: 40rpx;
  overflow: hidden;
  background: var(--panel-sunk);
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

.jx-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.jx-card {
  position: relative;
  width: calc(50% - 8rpx);
  box-sizing: border-box;
  padding: 18rpx 16rpx;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 16rpx;
}

.jx-card--win {
  border: 3rpx solid var(--gold);
}

.jx-card--dead .jx-card__text {
  opacity: 0.55;
}

.jx-token {
  width: 72rpx;
  height: 72rpx;
}

.jx-token__text {
  font-size: 28rpx;
}

.jx-card__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jx-card__top {
  display: flex;
  align-items: baseline;
  gap: 10rpx;
}

.jx-card__no {
  color: var(--gold);
  font-size: 30rpx;
  font-weight: 900;
}

.jx-card__role {
  font-size: 26rpx;
  font-weight: 700;
  color: var(--camp-god);
}

.jx-card__role--wolf {
  color: var(--camp-wolf);
}

.jx-card__role--villager {
  color: var(--camp-villager);
}

.jx-card__role--third {
  color: var(--camp-third);
}

.jx-card__name {
  font-size: 22rpx;
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jx-card__state {
  position: absolute;
  right: 14rpx;
  top: 10rpx;
  font-size: 20rpx;
  color: var(--text-3);
}

.jx-button {
  flex: 1;
}
</style>
