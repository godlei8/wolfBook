<script>
export default {
  onLaunch() {
    console.log('Wolfbook app launch')
  },
}
</script>

<style lang="scss">
page {
  min-height: 100%;
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.12), transparent 28%),
    linear-gradient(180deg, #060606 0%, #0c0c0c 100%);
  color: #ffffff;
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}

.page-shell {
  min-height: 100vh;
  padding: 32rpx 24rpx 180rpx;
  box-sizing: border-box;
}

.hero-title {
  font-size: 56rpx;
  line-height: 1.04;
  font-weight: 700;
  letter-spacing: 2rpx;
}

.hero-subtitle {
  margin-top: 12rpx;
  color: #b0b0b0;
  font-size: 24rpx;
  line-height: 1.7;
}

.glass-card {
  background: linear-gradient(180deg, rgba(22, 22, 22, 0.98) 0%, rgba(10, 10, 10, 0.96) 100%);
  border-radius: 20rpx;
  box-shadow: 0 22rpx 48rpx rgba(0, 0, 0, 0.24);
}

.section-card {
  margin-top: 24rpx;
  padding: 28rpx;
}

.section-title {
  font-size: 30rpx;
  line-height: 1.2;
  font-weight: 700;
}

.section-desc {
  margin-top: 8rpx;
  color: #afafaf;
  font-size: 24rpx;
  line-height: 1.7;
}

.section-meta {
  color: #9d9d9d;
  font-size: 24rpx;
  line-height: 1.5;
}

.pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 100rpx;
  height: 48rpx;
  padding: 0 18rpx;
  box-sizing: border-box;
  border-radius: 999rpx;
  font-size: 22rpx;
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
  height: 88rpx;
  line-height: 88rpx;
  border-radius: 12rpx;
  padding: 0 28rpx;
  background: #ffc000;
  color: #000000;
  font-size: 28rpx;
  font-weight: 700;
}

.button-primary::after {
  border: none;
}

.empty-state {
  padding: 80rpx 28rpx;
  text-align: center;
  color: #949494;
  line-height: 1.8;
}

.field-input,
.field-textarea {
  margin-top: 12rpx;
  padding: 20rpx 22rpx;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #ffffff;
  box-sizing: border-box;
}

.field-textarea {
  min-height: 180rpx;
  width: 100%;
}

.chip-row {
  display: flex;
  gap: 12rpx;
  overflow-x: auto;
  white-space: nowrap;
  padding-bottom: 8rpx;
}

.chip {
  display: inline-flex;
  align-items: center;
  padding: 10rpx 20rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.05);
  color: #c0c0c0;
  font-size: 24rpx;
}

.chip.active {
  background: rgba(255, 192, 0, 0.16);
  color: #ffc000;
}

.floating-plus {
  position: fixed;
  right: 28rpx;
  bottom: calc(env(safe-area-inset-bottom) + 132rpx);
  width: 104rpx;
  height: 104rpx;
  border-radius: 18rpx;
  background: #ffc000;
  color: #000000;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 56rpx;
  font-weight: 700;
  z-index: 20;
  box-shadow: 0 18rpx 36rpx rgba(255, 192, 0, 0.18);
}
</style>
