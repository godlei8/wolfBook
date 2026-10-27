<script setup>
// 立绘卡牌（原型「卡牌」）：3:4 立绘，底色是角色主题色，下面一条黑名牌带阵营圆点。
// 没有图时显示卡背（夜底 + 金色内框 + 月亮）。宽度跟随父元素。
defineProps({
  image: {
    type: String,
    default: '',
  },
  accent: {
    type: String,
    default: '#211C2B',
  },
  name: {
    type: String,
    default: '',
  },
  campFill: {
    type: String,
    default: '#6B6280',
  },
  showPlate: {
    type: Boolean,
    default: true,
  },
})
</script>

<template>
  <view class="art-card">
    <view class="art-card-frame" :style="{ backgroundColor: image ? accent : '#211C2B' }">
      <image v-if="image" class="art-card-image" :src="image" mode="aspectFill" webp />
      <view v-else class="art-card-back">
        <view class="art-card-back-line" />
        <text class="art-card-moon">☾</text>
      </view>
    </view>
    <view v-if="showPlate" class="art-card-plate">
      <text class="art-card-name serif" :class="{ 'art-card-name--muted': !image }">{{ name }}</text>
      <view class="art-card-pip" :style="{ backgroundColor: campFill }" />
    </view>
  </view>
</template>

<style scoped lang="scss">
.art-card {
  border: 4rpx solid var(--ink);
  border-radius: 24rpx;
  box-shadow: 6rpx 6rpx 0 var(--ink);
  overflow: hidden;
  background: var(--ink);
}

/* 3:4 比例：padding 撑高，图片绝对定位铺满 */
.art-card-frame {
  position: relative;
  width: 100%;
  padding-top: 133.33%;
}

.art-card-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}

.art-card-back {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.art-card-back-line {
  position: absolute;
  inset: 14rpx;
  border: 3rpx solid var(--gold);
  border-radius: 14rpx;
  opacity: 0.55;
}

.art-card-moon {
  color: var(--gold);
  font-size: 56rpx;
}

.art-card-plate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10rpx;
  padding: 12rpx 16rpx;
  background: var(--ink);
}

.art-card-name {
  min-width: 0;
  color: var(--text);
  font-size: 28rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.art-card-name--muted {
  color: var(--text-2);
}

.art-card-pip {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
