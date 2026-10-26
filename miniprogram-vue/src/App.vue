<script>
export default {
  onLaunch() {
    console.log('Wolfbook app launch')
  },
}
</script>

<style lang="scss">
page {
  --page-gutter: 24rpx;
  --page-top-space: 32rpx;
  --page-bottom-space: calc(env(safe-area-inset-bottom) + 188rpx);
  --page-bottom-with-fixed-bar: calc(env(safe-area-inset-bottom) + 228rpx);
  --card-radius: 24rpx;
  --card-padding: 30rpx;
  --section-gap: 22rpx;
  --content-gap: 16rpx;
  --chip-gap: 12rpx;
  --field-height: 84rpx;
  --button-height: 88rpx;
  --compact-button-height: 72rpx;
  min-height: 100%;
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.14), transparent 28%),
    radial-gradient(circle at left top, rgba(255, 255, 255, 0.03), transparent 30%),
    linear-gradient(180deg, #060606 0%, #0c0c0c 100%);
  color: #ffffff;
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}

.page-shell {
  min-height: 100vh;
  padding: var(--page-top-space) var(--page-gutter) var(--page-bottom-space);
  box-sizing: border-box;
}

.page-shell--with-fixed-bar {
  padding-bottom: var(--page-bottom-with-fixed-bar);
}

.page-bottom-spacer {
  height: calc(env(safe-area-inset-bottom) + 172rpx);
  flex-shrink: 0;
}

.hero-title {
  font-size: 54rpx;
  line-height: 1.08;
  font-weight: 700;
  letter-spacing: 1.5rpx;
}

.hero-subtitle {
  margin-top: 14rpx;
  color: #b6b6b6;
  font-size: 24rpx;
  line-height: 1.75;
}

.glass-card {
  background: linear-gradient(180deg, rgba(22, 22, 22, 0.98) 0%, rgba(10, 10, 10, 0.96) 100%);
  border-radius: var(--card-radius);
  border: 1rpx solid rgba(255, 255, 255, 0.04);
  box-shadow: 0 22rpx 44rpx rgba(0, 0, 0, 0.24);
}

.section-card {
  margin-top: var(--section-gap);
  padding: var(--card-padding);
}

.section-title {
  font-size: 32rpx;
  line-height: 1.24;
  font-weight: 700;
}

.section-desc {
  margin-top: 10rpx;
  color: #afafaf;
  font-size: 24rpx;
  line-height: 1.75;
}

.section-meta {
  color: #9d9d9d;
  font-size: 22rpx;
  line-height: 1.55;
}

.pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 100rpx;
  min-height: 52rpx;
  padding: 0 20rpx;
  box-sizing: border-box;
  border-radius: 999rpx;
  font-size: 22rpx;
  line-height: 1.2;
}

.pill-gold {
  background: rgba(255, 192, 0, 0.14);
  color: #ffc000;
}

.pill-white {
  background: rgba(255, 255, 255, 0.08);
  color: #f2f2f2;
}

.button-primary {
  height: var(--button-height);
  line-height: var(--button-height);
  border-radius: 16rpx;
  padding: 0 28rpx;
  background: linear-gradient(135deg, #ffd24f, #f3b91f);
  color: #171105;
  font-size: 28rpx;
  font-weight: 700;
  box-shadow: 0 14rpx 28rpx rgba(255, 192, 0, 0.18);
}

.button-primary::after,
.button-ghost::after,
.button-danger::after {
  border: none;
}

.button-ghost,
.button-danger {
  height: var(--button-height);
  line-height: var(--button-height);
  padding: 0 28rpx;
  border-radius: 16rpx;
  font-size: 28rpx;
  font-weight: 700;
}

.button-ghost {
  background: rgba(255, 255, 255, 0.04);
  color: #f3eee1;
  border: 1rpx solid rgba(255, 255, 255, 0.08);
}

.button-danger {
  background: linear-gradient(135deg, rgba(195, 76, 62, 0.92), rgba(135, 34, 24, 0.96));
  color: #fff4ef;
  box-shadow: 0 14rpx 26rpx rgba(156, 45, 32, 0.22);
}

.empty-state {
  padding: 88rpx 32rpx;
  text-align: center;
  color: #949494;
  line-height: 1.85;
}

.field-input,
.field-textarea {
  margin-top: 14rpx;
  padding: 20rpx 22rpx;
  border-radius: 18rpx;
  background: rgba(255, 255, 255, 0.045);
  border: 1rpx solid rgba(255, 255, 255, 0.04);
  color: #ffffff;
  box-sizing: border-box;
  font-size: 28rpx;
  line-height: 1.45;
}

.field-input {
  min-height: var(--field-height);
}

textarea.field-textarea {
  min-height: 180rpx;
  width: 100%;
}

.chip-row {
  display: flex;
  gap: var(--chip-gap);
  overflow-x: auto;
  white-space: nowrap;
  padding-bottom: 10rpx;
}

.chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 64rpx;
  padding: 0 22rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #c0c0c0;
  font-size: 24rpx;
  line-height: 1.2;
  box-sizing: border-box;
}

.chip.active {
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
}

.floating-plus {
  position: fixed;
  right: 24rpx;
  bottom: calc(env(safe-area-inset-bottom) + 146rpx);
  width: 98rpx;
  height: 98rpx;
  border-radius: 22rpx;
  background: linear-gradient(135deg, #ffd24f, #f3b91f);
  color: #171105;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 52rpx;
  font-weight: 700;
  z-index: 20;
  box-shadow: 0 18rpx 36rpx rgba(255, 192, 0, 0.18);
}
</style>
