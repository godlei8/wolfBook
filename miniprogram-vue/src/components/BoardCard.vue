<script setup>
// 板子卡片（原型 D1）：顶部 132px 横幅是板子自己的背景图，没有时摆三张阵容立绘；
// 板子名压在左下角黑铭牌上，下面是「人数 · 难度 · 胜利条件」、阵容头像筹码和阵营比例条。
// 头像和立绘直接写结构 + 全局类（App.vue 的 nt-*），不再嵌套子组件。
const props = defineProps({
  board: {
    type: Object,
    required: true,
  },
  // 收藏页才显示右上角的爱心
  showFavorite: {
    type: Boolean,
    default: false,
  },
  favorite: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['select', 'favorite-toggle'])

// 没有背景图时三张小立绘的位置（原型：62px 宽，左 / 中 / 右，-10° / 0 / 10°）
const FALLBACK_SLOTS = {
  l1: 'left: 330rpx; top: 60rpx; transform: rotate(-10deg);',
  c: 'left: 430rpx; top: 32rpx; transform: rotate(0deg); z-index: 2;',
  r1: 'left: 530rpx; top: 60rpx; transform: rotate(10deg);',
}

function meta(board) {
  return [board.playerCount ? `${board.playerCount} 人` : '', board.difficulty, board.winCondition].filter(Boolean).join(' · ')
}

function handleSelect() {
  emit('select', props.board.id)
}

function handleFavorite() {
  emit('favorite-toggle', props.board.id)
}
</script>

<template>
  <view class="nt-ink bc" @tap="handleSelect">
    <view class="bc-banner">
      <image v-if="board.backgroundImage" class="bc-bg" :src="board.backgroundImage" mode="aspectFill" webp />
      <block v-else>
        <view
          v-for="art in board.lineupArt || []"
          :key="art.roleId"
          class="nt-card bc-mini"
          :style="FALLBACK_SLOTS[art.slot] || FALLBACK_SLOTS.c"
        >
          <view class="nt-card__frame" :style="{ backgroundColor: art.accent }">
            <image class="nt-card__img" :src="art.image" mode="aspectFill" webp />
          </view>
        </view>
      </block>

      <view v-if="board.tags && board.tags.length" class="bc-tags">
        <text v-for="tag in board.tags.slice(0, 2)" :key="tag" class="nt-tag nt-tag--ink">{{ tag }}</text>
      </view>
      <view v-if="showFavorite" class="bc-fav" :class="{ 'bc-fav--on': favorite }" @tap.stop="handleFavorite">
        {{ favorite ? '♥' : '♡' }}
      </view>
      <view class="bc-plate serif">{{ board.name }}</view>
    </view>

    <view class="bc-body">
      <text class="nt-meta">{{ meta(board) }}</text>

      <view v-if="board.cardRoles && board.cardRoles.length" class="bc-tokens">
        <view
          v-for="role in board.cardRoles"
          :key="`${board.id}-${role.roleId}`"
          class="nt-token bc-token"
        >
          <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
          <view v-else class="nt-token__text bc-token__text" :style="{ borderColor: role.accent }">{{ (role.name || '?').slice(0, 1) }}</view>
          <text v-if="role.count > 1" class="nt-token__count">×{{ role.count }}</text>
        </view>
      </view>

      <view v-if="board.campSegments && board.campSegments.length" class="nt-camp">
        <view class="nt-camp__track">
          <view
            v-for="segment in board.campSegments"
            :key="segment.key"
            class="nt-camp__seg"
            :style="{ flex: segment.count, backgroundColor: segment.fill }"
          />
        </view>
        <view class="nt-camp__labels">
          <view v-for="segment in board.campSegments" :key="segment.key" class="nt-camp__label">
            <view class="nt-dot" :style="{ backgroundColor: segment.fill }" />
            <text>{{ segment.label }} {{ segment.count }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.bc {
  overflow: hidden;
}

.bc-banner {
  position: relative;
  height: 264rpx;
  background: #1b1724;
  border-bottom: 4rpx solid var(--ink);
  overflow: hidden;
}

.bc-bg {
  width: 100%;
  height: 100%;
  display: block;
}

.bc-mini {
  position: absolute;
  width: 124rpx;
  border-radius: 16rpx;
  box-shadow: 4rpx 4rpx 0 var(--ink);
}

.bc-tags {
  position: absolute;
  left: 20rpx;
  top: 20rpx;
  z-index: 3;
  display: flex;
  gap: 12rpx;
}

.bc-fav {
  position: absolute;
  right: 18rpx;
  top: 18rpx;
  z-index: 3;
  width: 64rpx;
  height: 64rpx;
  border-radius: 20rpx;
  background: var(--ink);
  color: var(--text);
  font-size: 32rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.bc-fav--on {
  color: var(--gold);
}

.bc-plate {
  position: absolute;
  left: 0;
  bottom: 0;
  z-index: 3;
  max-width: 76%;
  padding: 16rpx 32rpx 14rpx;
  border-top-right-radius: 28rpx;
  background: var(--ink);
  color: var(--text);
  font-size: 44rpx;
  font-weight: 900;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  box-sizing: border-box;
}

.bc-body {
  padding: 24rpx 32rpx 32rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.bc-tokens {
  display: flex;
  flex-wrap: wrap;
  gap: 18rpx;
  padding: 4rpx 0 4rpx 4rpx;
}

.bc-token {
  width: 68rpx;
  height: 68rpx;
}

.bc-token__text {
  font-size: 28rpx;
}
</style>
