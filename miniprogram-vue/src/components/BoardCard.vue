<script setup>
const props = defineProps({
  board: {
    type: Object,
    required: true,
  },
  metaText: {
    type: String,
    default: '',
  },
  favorite: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['select', 'favorite-toggle'])

function handleSelect() {
  emit('select', props.board.id)
}

function handleFavorite() {
  emit('favorite-toggle', props.board.id)
}

function shouldShowCount(role) {
  return role.count > 1 || role.faction === '狼人' || role.faction === '第三方' || role.roleType === '平民'
}
</script>

<template>
  <view class="glass-card board-card" @tap="handleSelect">
    <view class="board-cover-wrap">
      <image class="board-cover" :src="board.coverImage" mode="aspectFill" />
      <view class="board-favorite" :class="{ active: favorite }" @tap.stop="handleFavorite">
        {{ favorite ? '♥' : '♡' }}
      </view>
    </view>

    <view class="board-overlay">
      <view class="board-topline">
        <view class="pill pill-gold">{{ board.difficulty }}</view>
        <view v-if="metaText" class="section-meta">{{ metaText }}</view>
      </view>

      <view class="board-title-row">
        <view class="board-name">{{ board.name }}</view>
        <view class="board-player-badge">
          <view class="board-player-inner">
            <text class="board-player-number">{{ board.playerCount }}</text>
            <text class="board-player-unit">人</text>
          </view>
        </view>
      </view>

      <view v-if="board.cardRoles && board.cardRoles.length" class="board-role-row">
        <view
          v-for="role in board.cardRoles"
          :key="`${board.id}-${role.roleId}-${role.name}`"
          class="board-role-chip"
          :class="`role-tone-${role.toneClass}`"
        >
          <image class="board-role-avatar" :src="role.portrait" mode="aspectFill" />
          <text class="board-role-label">
            {{ shouldShowCount(role) ? `${role.count}${role.name}` : role.name }}
          </text>
        </view>
      </view>

      <view v-if="board.cardDescription || board.summary" class="board-summary">
        {{ board.cardDescription || board.summary }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.board-card {
  margin-top: 24rpx;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(18, 18, 18, 0.98) 0%, rgba(7, 7, 7, 0.98) 100%);
}

.board-cover-wrap {
  position: relative;
}

.board-cover {
  width: 100%;
  height: 320rpx;
  display: block;
}

.board-favorite {
  position: absolute;
  top: 20rpx;
  right: 20rpx;
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.52);
  color: rgba(255, 255, 255, 0.88);
  font-size: 32rpx;
  line-height: 1;
}

.board-favorite.active {
  color: #ffc000;
  background: rgba(8, 8, 8, 0.72);
}

.board-overlay {
  padding: 24rpx;
  background: linear-gradient(180deg, rgba(16, 16, 16, 0.98) 0%, rgba(8, 8, 8, 0.98) 100%);
}

.board-topline {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16rpx;
}

.board-title-row {
  margin-top: 16rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20rpx;
}

.board-name {
  min-width: 0;
  flex: 1;
  font-size: 38rpx;
  font-weight: 700;
  color: #ffffff;
  line-height: 1.2;
}

.board-player-badge {
  position: relative;
  flex-shrink: 0;
  width: 94rpx;
  height: 64rpx;
  padding: 2rpx;
  clip-path: polygon(14rpx 0, 100% 0, 100% calc(100% - 14rpx), calc(100% - 14rpx) 100%, 0 100%, 0 14rpx);
  background: linear-gradient(135deg, rgba(255, 213, 77, 0.95), rgba(145, 115, 0, 0.95));
  box-shadow:
    0 8rpx 18rpx rgba(255, 192, 0, 0.14),
    inset 0 0 0 1rpx rgba(255, 245, 214, 0.35);
}

.board-player-inner {
  width: 100%;
  height: 100%;
  clip-path: polygon(12rpx 0, 100% 0, 100% calc(100% - 12rpx), calc(100% - 12rpx) 100%, 0 100%, 0 12rpx);
  background: linear-gradient(180deg, rgba(26, 22, 8, 0.98), rgba(10, 9, 5, 0.98));
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2rpx;
  padding-left: 6rpx;
  box-sizing: border-box;
}

.board-player-number {
  color: #ffc000;
  font-size: 34rpx;
  font-weight: 700;
  line-height: 1;
}

.board-player-unit {
  color: #f7e3b0;
  font-size: 18rpx;
  letter-spacing: 1rpx;
  line-height: 1;
}

.board-role-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}

.board-role-chip {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  padding: 10rpx 16rpx 10rpx 10rpx;
  border-radius: 999rpx;
}

.role-tone-good {
  background: rgba(255, 192, 0, 0.12);
}

.role-tone-wolf {
  background: rgba(255, 89, 59, 0.12);
}

.role-tone-third {
  background: rgba(212, 170, 255, 0.12);
}

.board-role-avatar {
  width: 40rpx;
  height: 40rpx;
  border-radius: 999rpx;
}

.board-role-label {
  font-size: 22rpx;
  color: #ffffff;
}

.board-summary {
  margin-top: 18rpx;
  color: #8f8f8f;
  font-size: 24rpx;
  line-height: 1.7;
}
</style>
