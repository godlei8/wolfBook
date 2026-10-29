<script setup>
// 自定义导航栏（所有页面都是 navigationStyle: custom）。
// overlay 时浮在页面顶部的大图上；否则占位，内容从它下面开始。
import { getNavInsets, goBackOrHome } from '../utils/nav.js'

defineProps({
  title: {
    type: String,
    default: '',
  },
  back: {
    type: Boolean,
    default: false,
  },
  overlay: {
    type: Boolean,
    default: false,
  },
  wordmark: {
    type: String,
    default: '',
  },
})

const insets = getNavInsets()
</script>

<template>
  <view class="nav" :class="{ 'nav--overlay': overlay }" :style="{ paddingTop: `${insets.statusBar}px` }">
    <view class="nav__row" :style="{ height: `${insets.navBar}px`, paddingRight: `${insets.capsuleSpace}px` }">
      <view class="nav__center">
        <slot>
          <text v-if="title" class="nav__title">{{ title }}</text>
        </slot>
      </view>
      <view v-if="back" class="nav__back" @tap="goBackOrHome">‹</view>
      <text v-else-if="wordmark" class="nav__wordmark">{{ wordmark }}</text>
      <!-- 右上角是微信胶囊，页面自己的按钮（语音、分享）跟在返回键右边 -->
      <view class="nav__side"><slot name="side" /></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.nav {
  position: relative;
  z-index: 20;
  background: var(--bg);
}

.nav--overlay {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  background: transparent;
}

.nav__row {
  position: relative;
  display: flex;
  align-items: center;
  padding-left: 28rpx;
  box-sizing: border-box;
}

.nav__center {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav__title {
  color: var(--text);
  font-family: var(--serif);
  font-size: 34rpx;
  font-weight: 900;
}

.nav__back {
  position: relative;
  z-index: 1;
  width: 72rpx;
  height: 72rpx;
  border-radius: 24rpx;
  border: 4rpx solid var(--ink);
  background: var(--panel);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  color: var(--text);
  font-size: 48rpx;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  padding-bottom: 6rpx;
}

.nav__side {
  position: relative;
  z-index: 1;
  margin-left: 16rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.nav__wordmark {
  position: relative;
  z-index: 1;
  padding-left: 12rpx;
  color: var(--gold);
  font-family: var(--serif);
  font-size: 26rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
}
</style>
