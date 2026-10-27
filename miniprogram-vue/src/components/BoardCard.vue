<script setup>
// 板子列表卡片（原型 D1）：顶部是板子自己的背景图，没有时摆三张阵容立绘；
// 下面是人数难度、阵容头像筹码和阵营比例条。粗黑描边 + 硬投影，像摆在桌上的卡牌。
import RoleToken from './RoleToken.vue'

const props = defineProps({
  boardId: {
    type: Number,
    required: true,
  },
  name: {
    type: String,
    default: '',
  },
  playerCount: {
    type: [Number, String],
    default: '',
  },
  difficulty: {
    type: String,
    default: '',
  },
  tags: {
    type: Array,
    default: () => [],
  },
  // 板子自己的背景图；没有时用 lineupArt（utils/board-rules.js 的 pickLineupArt 挑好的三张立绘）
  backgroundImage: {
    type: String,
    default: '',
  },
  lineupArt: {
    type: Array,
    default: () => [],
  },
  // 背景图和立绘都没有时的最后兜底
  coverImage: {
    type: String,
    default: '',
  },
  cardRoles: {
    type: Array,
    default: () => [],
  },
  // 阵营比例条，来自 campBreakdown
  campSegments: {
    type: Array,
    default: () => [],
  },
  description: {
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
  emit('select', props.boardId)
}

function handleFavorite() {
  emit('favorite-toggle', props.boardId)
}

function lineupSlot(art, index) {
  if (art.center) return 'center'
  return index === 0 ? 'left' : 'right'
}
</script>

<template>
  <view class="board-card ink-card" @tap="handleSelect">
    <view class="board-banner">
      <image v-if="backgroundImage" class="banner-image" :src="backgroundImage" mode="aspectFill" webp />
      <view v-else-if="lineupArt.length" class="banner-lineup">
        <view
          v-for="(art, index) in lineupArt"
          :key="`${boardId}-art-${art.roleId}`"
          class="lineup-card"
          :class="`lineup-card--${lineupSlot(art, index)}`"
          :style="{ backgroundColor: art.accent }"
        >
          <image class="lineup-card-image" :src="art.image" mode="aspectFill" webp />
        </view>
      </view>
      <image v-else-if="coverImage" class="banner-image" :src="coverImage" mode="aspectFill" />

      <view v-if="tags.length" class="banner-tags">
        <text v-for="tag in tags.slice(0, 2)" :key="tag" class="banner-tag">{{ tag }}</text>
      </view>
      <view class="banner-favorite" :class="{ active: favorite }" @tap.stop="handleFavorite">
        {{ favorite ? '♥' : '♡' }}
      </view>
      <view class="banner-plate serif">{{ name }}</view>
    </view>

    <view class="board-body">
      <view class="board-meta">
        <text>{{ playerCount }} 人</text>
        <text v-if="difficulty"> · {{ difficulty }}</text>
      </view>

      <view v-if="cardRoles.length" class="board-chips">
        <RoleToken
          v-for="role in cardRoles"
          :key="`${boardId}-${role.roleId}-${role.name}`"
          :image="role.portrait"
          :accent="role.accent"
          :count="role.count"
        />
      </view>

      <view v-if="campSegments.length" class="camp-bar">
        <view class="camp-bar-track">
          <view
            v-for="segment in campSegments"
            :key="segment.key"
            class="camp-bar-segment"
            :style="{ flex: segment.count, backgroundColor: segment.fill }"
          />
        </view>
        <view class="camp-bar-labels">
          <view v-for="segment in campSegments" :key="segment.key" class="camp-bar-label">
            <view class="camp-dot" :style="{ backgroundColor: segment.fill }" />
            <text>{{ segment.label }} {{ segment.count }}</text>
          </view>
        </view>
      </view>

      <view v-if="description" class="board-summary">{{ description }}</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.board-card {
  margin-top: 32rpx;
  overflow: hidden;
}

.board-banner {
  position: relative;
  height: 264rpx;
  background: #1b1724;
  border-bottom: 4rpx solid var(--ink);
  overflow: hidden;
}

.banner-image {
  width: 100%;
  height: 100%;
  display: block;
}

/* 三张立绘摆在右侧，左下角留给板子名铭牌 */
.banner-lineup {
  position: absolute;
  top: 0;
  right: 96rpx;
  bottom: 0;
  display: flex;
  align-items: center;
}

.lineup-card {
  position: relative;
  width: 124rpx;
  height: 165rpx;
  border: 4rpx solid var(--ink);
  border-radius: 16rpx;
  box-shadow: 4rpx 4rpx 0 var(--ink);
  overflow: hidden;
  box-sizing: border-box;
}

.lineup-card-image {
  width: 100%;
  height: 100%;
  display: block;
}

.lineup-card--left {
  margin-right: -22rpx;
  transform: translateY(20rpx) rotate(-10deg);
}

.lineup-card--center {
  z-index: 2;
  width: 136rpx;
  height: 181rpx;
  transform: translateY(-4rpx);
}

.lineup-card--right {
  margin-left: -22rpx;
  transform: translateY(20rpx) rotate(10deg);
}

.banner-tags {
  position: absolute;
  top: 20rpx;
  left: 20rpx;
  display: flex;
  gap: 12rpx;
  z-index: 3;
}

.banner-tag {
  padding: 6rpx 16rpx;
  border-radius: 12rpx;
  background: var(--ink);
  color: var(--text);
  font-size: 22rpx;
  line-height: 1.4;
}

.banner-favorite {
  position: absolute;
  top: 18rpx;
  right: 18rpx;
  z-index: 3;
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 20rpx;
  background: var(--ink);
  color: var(--text);
  font-size: 32rpx;
  line-height: 1;
}

.banner-favorite.active {
  color: var(--gold);
}

.banner-plate {
  position: absolute;
  left: 0;
  bottom: 0;
  z-index: 3;
  max-width: 70%;
  padding: 14rpx 32rpx 12rpx;
  border-top-right-radius: 28rpx;
  background: var(--ink);
  color: var(--text);
  font-size: 40rpx;
  font-weight: 900;
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  box-sizing: border-box;
}

.board-body {
  padding: 22rpx 30rpx 30rpx;
}

.board-meta {
  color: var(--text-2);
  font-size: 26rpx;
  line-height: 1.5;
}

.board-chips {
  margin-top: 22rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 18rpx;
  padding-left: 4rpx;
}

.camp-bar {
  margin-top: 30rpx;
}

.camp-bar-track {
  display: flex;
  gap: 4rpx;
  height: 12rpx;
  border-radius: 6rpx;
  overflow: hidden;
}

.camp-bar-segment {
  height: 100%;
}

.camp-bar-labels {
  margin-top: 14rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 28rpx;
  color: var(--text-2);
  font-size: 22rpx;
}

.camp-bar-label {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.camp-dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
}

.board-summary {
  margin-top: 20rpx;
  color: var(--text-3);
  font-size: 24rpx;
  line-height: 1.7;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
</style>
