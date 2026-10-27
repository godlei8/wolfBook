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
  /* 夜镇卡牌配色（原型 D · 设计规范）：立绘是最亮的东西，界面只用灯火金和月光青两个强调色 */
  --bg: #15121c;
  --panel: #211c2b;
  --panel-raised: #2c2539;
  --panel-line: #352d44;
  --ink: #07060a;
  --text: #f4ead5;
  --text-2: #b8adc9;
  --text-3: #9a90ae;
  --gold: #f2b33d;
  --moon: #5fe3e0;
  --camp-god: #e9c46a;
  --camp-villager: #8fd0a6;
  --camp-wolf: #ff7468;
  --camp-third: #f7a8cb;
  --serif: "Noto Serif SC", "Songti SC", "STSong", "SimSun", serif;
  min-height: 100%;
  background: var(--bg);
  color: var(--text);
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
  font-family: var(--serif);
  font-size: 56rpx;
  line-height: 1.2;
  font-weight: 900;
  letter-spacing: 1rpx;
}

.hero-subtitle {
  margin-top: 14rpx;
  color: var(--text-2);
  font-size: 24rpx;
  line-height: 1.75;
}

/* 面板：夜色抬起一层，细描线，不加投影 */
.glass-card {
  background: var(--panel);
  border-radius: var(--card-radius);
  border: 2rpx solid var(--panel-line);
}

/* 可以拿起来的东西（卡牌、按钮）：粗黑描边 + 硬投影 */
.ink-card {
  background: var(--panel);
  border: 4rpx solid var(--ink);
  border-radius: 36rpx;
  box-shadow: 8rpx 8rpx 0 var(--ink);
}

.serif {
  font-family: var(--serif);
}

.section-card {
  margin-top: var(--section-gap);
  padding: var(--card-padding);
}

.section-title {
  font-family: var(--serif);
  font-size: 34rpx;
  line-height: 1.3;
  font-weight: 900;
}

.section-desc {
  margin-top: 10rpx;
  color: var(--text-2);
  font-size: 24rpx;
  line-height: 1.75;
}

.section-meta {
  color: var(--text-3);
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
  background: rgba(242, 179, 61, 0.16);
  color: var(--gold);
}

.pill-white {
  background: var(--panel-raised);
  color: var(--text);
}

.button-primary {
  height: var(--button-height);
  line-height: calc(var(--button-height) - 8rpx);
  border-radius: 28rpx;
  padding: 0 28rpx;
  background: var(--gold);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  box-sizing: border-box;
  font-size: 28rpx;
  font-weight: 700;
}

.button-primary:active,
.button-ghost:active {
  transform: translate(6rpx, 6rpx);
  box-shadow: none;
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
  line-height: calc(var(--button-height) - 8rpx);
  background: var(--panel-raised);
  color: var(--text);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  box-sizing: border-box;
}

.button-danger {
  background: linear-gradient(135deg, rgba(195, 76, 62, 0.92), rgba(135, 34, 24, 0.96));
  color: #fff4ef;
  box-shadow: 0 14rpx 26rpx rgba(156, 45, 32, 0.22);
}

.empty-state {
  padding: 88rpx 32rpx;
  text-align: center;
  color: var(--text-3);
  line-height: 1.85;
}

.field-input,
.field-textarea {
  margin-top: 14rpx;
  padding: 20rpx 22rpx;
  border-radius: 18rpx;
  background: var(--panel);
  border: 2rpx solid #3a3149;
  color: var(--text);
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
  background: #1c1824;
  border: 3rpx solid #3a3149;
  color: #c9bfd8;
  font-size: 24rpx;
  line-height: 1.2;
  box-sizing: border-box;
}

.chip.active {
  background: var(--text);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  font-weight: 700;
}

.floating-plus {
  position: fixed;
  right: 24rpx;
  bottom: calc(env(safe-area-inset-bottom) + 146rpx);
  width: 98rpx;
  height: 98rpx;
  border-radius: 28rpx;
  background: var(--gold);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 52rpx;
  font-weight: 700;
  z-index: 20;
}
</style>
