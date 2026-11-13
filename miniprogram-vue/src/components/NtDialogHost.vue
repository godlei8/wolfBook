<script setup>
// 画 utils/dialog.js 的弹窗和底部弹出菜单（夜镇卡牌风格）。每个要弹窗的页面在模板最后放一个 <NtDialogHost />；
// 页面里的视图组件直接调 utils/dialog.js 的 showModal / showActionSheet，不用自己嵌组件。
import { dialogState, pick } from '../utils/dialog.js'

const state = dialogState
</script>

<template>
  <view v-if="state.kind" class="dlg" @touchmove.stop.prevent>
    <view class="dlg__scrim" @tap="pick('cancel')" />

    <view v-if="state.kind === 'modal'" class="dlg__card">
      <text v-if="state.title" class="dlg__title">{{ state.title }}</text>
      <text v-if="state.content" class="dlg__content">{{ state.content }}</text>
      <view class="dlg__buttons">
        <view v-if="state.showCancel" class="dlg__btn dlg__btn--ghost" @tap="pick('cancel')">{{ state.cancelText }}</view>
        <view class="dlg__btn" :class="state.danger ? 'dlg__btn--danger' : 'dlg__btn--gold'" @tap="pick('confirm')">{{ state.confirmText }}</view>
      </view>
    </view>

    <view v-else class="dlg__sheet">
      <view class="dlg__handle" />
      <text v-if="state.title" class="dlg__sheet-title" :class="{ 'dlg__sheet-title--lead': state.content }">{{ state.title }}</text>
      <text v-if="state.content" class="dlg__sheet-content">{{ state.content }}</text>
      <view class="dlg__items">
        <view
          v-for="(item, index) in state.items"
          :key="index"
          class="dlg__item"
          :class="{ 'dlg__item--danger': item.danger }"
          @tap="pick(index)"
        >{{ item.label }}</view>
      </view>
      <view class="dlg__btn dlg__btn--ghost dlg__cancel" @tap="pick('cancel')">取消</view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.dlg {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
}

.dlg__scrim {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: var(--scrim-strong);
}

.dlg__card {
  position: relative;
  width: 600rpx;
  box-sizing: border-box;
  padding: 44rpx 40rpx 36rpx;
  border-radius: 40rpx;
  background: var(--panel);
  border: 2rpx solid var(--line-strong);
  box-shadow: 0 8rpx 0 var(--ink);
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.dlg__title {
  color: var(--text);
  font-family: var(--serif);
  font-size: 38rpx;
  font-weight: 900;
  line-height: 1.3;
}

.dlg__content {
  color: var(--text-2);
  font-size: 28rpx;
  line-height: 1.6;
}

.dlg__buttons {
  margin-top: 16rpx;
  display: flex;
  gap: 16rpx;
}

.dlg__btn {
  flex: 1;
  height: 96rpx;
  border-radius: 28rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30rpx;
  font-weight: 700;
}

.dlg__btn--ghost {
  background: var(--panel-raised);
  color: var(--text);
}

.dlg__btn--gold {
  background: var(--gold);
  color: var(--bg);
}

.dlg__btn--danger {
  background: var(--camp-wolf);
  color: var(--bg);
}

.dlg__sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  box-sizing: border-box;
  padding: 20rpx 32rpx calc(env(safe-area-inset-bottom) + 32rpx);
  border-radius: 48rpx 48rpx 0 0;
  background: var(--panel);
  border-top: 2rpx solid var(--line-strong);
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.dlg__handle {
  align-self: center;
  width: 80rpx;
  height: 8rpx;
  border-radius: 999rpx;
  background: var(--line-strong);
}

.dlg__sheet-title {
  color: var(--text-2);
  font-size: 26rpx;
  text-align: center;
}

.dlg__sheet-title--lead {
  color: var(--text);
  font-family: var(--serif);
  font-size: 34rpx;
  font-weight: 900;
  text-align: left;
}

.dlg__sheet-content {
  padding: 18rpx 22rpx;
  border-radius: 22rpx;
  background: var(--panel-sunk);
  color: var(--text-soft);
  font-size: 25rpx;
  line-height: 1.6;
}

.dlg__items {
  display: flex;
  flex-direction: column;
  border-radius: 28rpx;
  overflow: hidden;
  background: var(--panel-raised);
}

.dlg__item {
  height: 104rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text);
  font-size: 30rpx;
  font-weight: 700;
  border-top: 2rpx solid var(--panel-line);
}

.dlg__item:first-child {
  border-top: 0;
}

.dlg__item--danger {
  color: var(--camp-wolf);
}

.dlg__cancel {
  flex: none;
  background: var(--panel-sunk);
  color: var(--text-2);
}
</style>
