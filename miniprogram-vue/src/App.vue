<script>
export default {
  onLaunch() {
    console.log('Wolfbook app launch')
  },
}
</script>

<style lang="scss">
/*
 * 夜镇卡牌（设计原型 D）全局样式。
 * 原型以 390px 宽画，这里 1px = 2rpx。所有页面都用自定义导航栏（components/NavBar.vue）。
 * 组件写成全局类（nt-*），页面和组件里直接用结构 + 类名，不再套一层自定义组件，
 * 避免小程序里组件嵌套组件时渲染不出来。
 */
page {
  --page-gutter: 32rpx;
  --card-radius: 36rpx;
  --bg: #15121c;
  --panel: #211c2b;
  --panel-raised: #2c2539;
  --panel-line: #352d44;
  --line: #3a3149;
  --ink: #07060a;
  --bar-bg: #0e0c13;
  --text: #f4ead5;
  --text-2: #b8adc9;
  --text-3: #9a90ae;
  --text-soft: #d9cfe6;
  --text-4: #6b6280;
  --panel-sunk: #1b1724;
  --line-strong: #4a4060;
  --scrim: rgba(7, 6, 10, 0.6);
  --scrim-strong: rgba(7, 6, 10, 0.86);
  --warn-bg: rgba(214, 92, 92, 0.16);
  --gold: #f2b33d;
  --moon: #5fe3e0;
  --camp-god: #e9c46a;
  --camp-villager: #8fd0a6;
  --camp-wolf: #ff7468;
  --camp-third: #f7a8cb;
  --serif: "Noto Serif SC", "Songti SC", "STSong", "SimSun", serif;
  --tabbar-space: calc(env(safe-area-inset-bottom) + 200rpx);
  --bar-space: calc(env(safe-area-inset-bottom) + 200rpx);
  min-height: 100%;
  background: var(--bg);
  color: var(--text);
  font-family: "PingFang SC", "Noto Sans SC", "Microsoft YaHei", sans-serif;
  font-size: 30rpx;
}

/* ---------- 页面骨架 ---------- */
.nt-page {
  min-height: 100vh;
  background: var(--bg);
  box-sizing: border-box;
}

.nt-page--tab {
  padding-bottom: var(--tabbar-space);
}

.nt-page--bar {
  padding-bottom: var(--bar-space);
}

.nt-gutter {
  padding-left: 40rpx;
  padding-right: 40rpx;
}

.nt-stack {
  display: flex;
  flex-direction: column;
  gap: 32rpx;
}

.serif {
  font-family: var(--serif);
}

.c-muted {
  color: var(--text-2);
}

.c-faint {
  color: var(--text-3);
}

.c-gold {
  color: var(--gold);
}

.c-moon {
  color: var(--moon);
}

/* ---------- 标题 ---------- */
.nt-wordmark {
  color: var(--gold);
  font-family: var(--serif);
  font-size: 26rpx;
  font-weight: 700;
  letter-spacing: 8rpx;
}

.nt-title {
  font-family: var(--serif);
  font-size: 60rpx;
  line-height: 1.25;
  font-weight: 900;
  color: var(--text);
}

.nt-h2 {
  font-family: var(--serif);
  font-size: 38rpx;
  line-height: 1.3;
  font-weight: 900;
  color: var(--text);
}

.nt-label {
  color: var(--text-2);
  font-size: 26rpx;
  font-weight: 700;
}

.nt-meta {
  color: var(--text-2);
  font-size: 26rpx;
}

/* ---------- 面板与卡片 ---------- */
.nt-panel {
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
  border-radius: 36rpx;
  padding: 36rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
  box-sizing: border-box;
}

.nt-panel--tight {
  gap: 8rpx;
}

.nt-panel__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20rpx;
}

/* 可以拿起来的东西：粗黑描边 + 硬投影 */
.nt-ink {
  background: var(--panel);
  border: 4rpx solid var(--ink);
  border-radius: 36rpx;
  box-shadow: 8rpx 8rpx 0 var(--ink);
  box-sizing: border-box;
}

.nt-raised {
  background: var(--panel-raised);
  border-radius: 28rpx;
}

/* ---------- 按钮 ---------- */
.nt-btn {
  height: 96rpx;
  padding: 0 40rpx;
  border-radius: 28rpx;
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  font-size: 32rpx;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  white-space: nowrap;
}

.nt-btn:active,
.nt-iconbtn:active {
  transform: translate(6rpx, 6rpx);
  box-shadow: none;
}

.nt-btn--gold {
  background: var(--gold);
  color: var(--ink);
}

.nt-btn--ghost {
  background: var(--panel-raised);
  color: var(--text);
}

.nt-btn--cream {
  background: var(--text);
  color: var(--ink);
}

.nt-btn--block {
  display: flex;
  width: 100%;
}

.nt-btn--small {
  height: 76rpx;
  padding: 0 28rpx;
  border-radius: 24rpx;
  font-size: 28rpx;
}

.nt-btn--disabled {
  opacity: 0.5;
}

.nt-iconbtn {
  width: 88rpx;
  height: 88rpx;
  border-radius: 28rpx;
  border: 4rpx solid var(--ink);
  background: var(--panel);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  flex-shrink: 0;
  font-size: 40rpx;
  line-height: 1;
}

/* ---------- 筛选与标签 ---------- */
.nt-chips {
  white-space: nowrap;
  box-sizing: border-box;
}

.nt-chips__inner {
  display: inline-flex;
  gap: 16rpx;
  padding: 4rpx 40rpx 12rpx;
}

.nt-chip {
  height: 68rpx;
  padding: 0 28rpx;
  border-radius: 34rpx;
  border: 3rpx solid var(--line);
  background: #1c1824;
  color: #c9bfd8;
  font-size: 28rpx;
  display: inline-flex;
  align-items: center;
  gap: 12rpx;
  box-sizing: border-box;
  white-space: nowrap;
  flex-shrink: 0;
}

.nt-chip--on {
  background: var(--text);
  color: var(--ink);
  border: 4rpx solid var(--ink);
  box-shadow: 4rpx 4rpx 0 var(--ink);
  font-weight: 700;
}

.nt-tag {
  display: inline-flex;
  align-items: center;
  padding: 6rpx 16rpx;
  border-radius: 12rpx;
  background: var(--panel-raised);
  color: var(--text-soft);
  font-size: 24rpx;
  line-height: 1.4;
  white-space: nowrap;
}

.nt-tag--ink {
  background: var(--ink);
  color: var(--text);
}

.nt-dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  flex-shrink: 0;
  display: inline-block;
}

/* ---------- 头像筹码：主题色描边 + 黑外圈 + 硬投影 ---------- */
.nt-token {
  position: relative;
  flex-shrink: 0;
  display: inline-block;
}

.nt-token__img {
  width: 100%;
  height: 100%;
  display: block;
  border-radius: 50%;
  border: 5rpx solid var(--text);
  box-sizing: border-box;
  background: var(--ink);
  box-shadow:
    0 0 0 4rpx var(--ink),
    4rpx 6rpx 0 4rpx var(--ink);
}

.nt-token--dead .nt-token__img {
  border-style: dashed;
  border-color: #6b6280 !important;
  filter: grayscale(1) brightness(0.55);
}

.nt-token__count {
  position: absolute;
  right: -16rpx;
  bottom: -12rpx;
  padding: 0 9rpx;
  border-radius: 16rpx;
  border: 3rpx solid var(--ink);
  background: var(--text);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 700;
  line-height: 28rpx;
}

.nt-token__text {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  border: 5rpx solid var(--line);
  box-sizing: border-box;
  background: var(--panel-raised);
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--serif);
  font-weight: 900;
  box-shadow:
    0 0 0 4rpx var(--ink),
    4rpx 6rpx 0 4rpx var(--ink);
}

/* ---------- 立绘卡牌：3:4，黑名牌；没图时显示卡背 ---------- */
.nt-card {
  border: 4rpx solid var(--ink);
  border-radius: 24rpx;
  box-shadow: 6rpx 6rpx 0 var(--ink);
  overflow: hidden;
  background: var(--ink);
  box-sizing: border-box;
}

.nt-card__frame {
  position: relative;
  width: 100%;
  padding-top: 133.33%;
  background: var(--panel);
}

.nt-card__img {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  display: block;
}

.nt-card__back {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--panel);
}

.nt-card__line {
  position: absolute;
  left: 14rpx;
  top: 14rpx;
  right: 14rpx;
  bottom: 14rpx;
  border: 3rpx solid var(--gold);
  border-radius: 14rpx;
  opacity: 0.55;
}

.nt-card__moon {
  color: var(--gold);
  font-size: 56rpx;
}

.nt-card__plate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10rpx;
  padding: 12rpx 18rpx;
  background: var(--ink);
}

.nt-card__name {
  min-width: 0;
  color: var(--text);
  font-family: var(--serif);
  font-size: 28rpx;
  font-weight: 900;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nt-card__name--muted {
  color: var(--text-2);
}

/* ---------- 阵营比例条 ---------- */
.nt-camp {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.nt-camp__track {
  display: flex;
  gap: 4rpx;
  height: 12rpx;
  border-radius: 6rpx;
  overflow: hidden;
}

.nt-camp__seg {
  height: 100%;
}

.nt-camp__labels {
  display: flex;
  flex-wrap: wrap;
  gap: 28rpx;
  color: var(--text-2);
  font-size: 24rpx;
}

.nt-camp__label {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

/* ---------- 列表行、问题条、折叠 ---------- */
.nt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  min-height: 104rpx;
  padding: 16rpx 24rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  box-sizing: border-box;
}

.nt-arrow {
  color: var(--text-3);
  font-size: 40rpx;
  line-height: 1;
  flex-shrink: 0;
}

.nt-qchip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  min-height: 88rpx;
  padding: 20rpx 28rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 28rpx;
  line-height: 1.5;
  box-sizing: border-box;
}

.nt-qchip__ask {
  color: var(--moon);
  font-weight: 700;
  flex-shrink: 0;
}

.nt-fold {
  border-top: 2rpx solid var(--panel-line);
  padding-top: 20rpx;
}

.nt-fold__head {
  min-height: 72rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.nt-fold__title {
  font-family: var(--serif);
  font-size: 34rpx;
  font-weight: 900;
}

.nt-fold__question {
  font-size: 30rpx;
  font-weight: 700;
  line-height: 1.5;
}

.nt-fold__arrow {
  color: var(--text-2);
  font-size: 40rpx;
  transform: rotate(90deg);
  transition: transform 0.2s;
}

.nt-fold__arrow--open {
  transform: rotate(-90deg);
}

.nt-bullets {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.nt-bullet {
  position: relative;
  padding-left: 30rpx;
  color: #e8dff0;
  font-size: 28rpx;
  line-height: 1.75;
}

.nt-bullet::before {
  content: '';
  position: absolute;
  left: 4rpx;
  top: 21rpx;
  width: 10rpx;
  height: 10rpx;
  border-radius: 50%;
  background: var(--text-2);
}

/* ---------- 输入框 ---------- */
.nt-field {
  min-height: 96rpx;
  padding: 0 28rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  display: flex;
  align-items: center;
  gap: 16rpx;
  box-sizing: border-box;
}

.nt-field__input {
  flex: 1;
  min-width: 0;
  height: 88rpx;
  color: var(--text);
  font-size: 30rpx;
}

.nt-textarea {
  width: 100%;
  min-height: 220rpx;
  padding: 24rpx 28rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  color: var(--text);
  font-size: 30rpx;
  line-height: 1.6;
  box-sizing: border-box;
}

.nt-placeholder {
  color: var(--text-3);
}

/* ---------- 底部固定操作栏 ---------- */
.nt-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom));
  background: var(--bar-bg);
  border-top: 4rpx solid var(--ink);
  box-sizing: border-box;
}

.nt-empty {
  padding: 80rpx 40rpx;
  text-align: center;
  color: var(--text-3);
  font-size: 28rpx;
  line-height: 1.8;
}

.nt-footer {
  padding: 36rpx 0 8rpx;
  text-align: center;
  color: var(--text-3);
  font-size: 24rpx;
}

/* 星星装饰（四角星） */
.nt-star {
  position: absolute;
  color: var(--gold);
  line-height: 1;
}

/* 电子法官牌桌（utils/judge/stage.js）：两侧座位 + 中间大卡片，下面是标题行和固定高度的操作面板 */
.nt-stage {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
  padding: 24rpx var(--page-gutter) 0;
}

.nt-stage__col {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.nt-stage__col--left {
  order: 0;
}

.nt-stage__col--right {
  order: 2;
}

.nt-stage__center {
  order: 1;
  position: relative;
  flex: 1;
  min-width: 0;
}

.nt-seat {
  position: relative;
  border-radius: 50%;
}

.nt-seat--dim {
  opacity: 0.45;
}

.nt-seat--on {
  box-shadow: 0 0 0 8rpx var(--moon), 0 0 28rpx 8rpx rgba(95, 227, 224, 0.5);
}

.nt-seat--speaking {
  box-shadow: 0 0 0 6rpx var(--gold), 0 0 28rpx 8rpx rgba(242, 179, 61, 0.45);
}

.nt-seat--out {
  box-shadow: 0 0 0 8rpx var(--camp-wolf);
}

.nt-seat__token {
  width: 100%;
  height: 100%;
}

.nt-seat__text {
  font-size: 28rpx;
}

.nt-seat__no {
  position: absolute;
  left: 50%;
  bottom: -10rpx;
  transform: translateX(-50%);
  min-width: 32rpx;
  padding: 0 8rpx;
  border-radius: 16rpx;
  border: 3rpx solid var(--ink);
  background: var(--gold);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 900;
  line-height: 28rpx;
  text-align: center;
}

.nt-seat__role {
  position: absolute;
  right: -6rpx;
  top: -6rpx;
  width: 34rpx;
  height: 34rpx;
  border-radius: 17rpx;
  border: 3rpx solid var(--ink);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 900;
  line-height: 34rpx;
  text-align: center;
}

.nt-seat__mate {
  position: absolute;
  right: 0;
  top: 0;
  width: 20rpx;
  height: 20rpx;
  border-radius: 10rpx;
  border: 3rpx solid var(--ink);
  background: var(--camp-wolf);
}

.nt-seat__tag {
  position: absolute;
  left: -8rpx;
  top: -8rpx;
  min-width: 32rpx;
  padding: 0 6rpx;
  border-radius: 16rpx;
  border: 3rpx solid var(--ink);
  background: var(--moon);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 900;
  line-height: 28rpx;
  text-align: center;
}

.nt-seat__tag--votes {
  background: var(--camp-wolf);
}

.nt-seat__tag--gold {
  background: var(--gold);
}

/* 法官视角的座位标记（judge-desk.js）：谁刀谁 / 投给谁、刀口、发言顺序 */
.nt-seat__tag--dark {
  background: var(--panel-raised);
  color: var(--text);
}

.nt-seat__tag--red {
  background: var(--camp-wolf);
}

.nt-seat__tag--done {
  background: var(--panel-raised);
  color: var(--text-4);
}

.nt-seat__count {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  min-width: 48rpx;
  padding: 0 10rpx;
  box-sizing: border-box;
  border-radius: 20rpx;
  border: 3rpx solid var(--ink);
  font-size: 24rpx;
  font-weight: 900;
  line-height: 36rpx;
  text-align: center;
  color: var(--ink);
}

.nt-seat__count--red {
  background: var(--camp-wolf);
}

.nt-seat__count--light {
  background: var(--text);
}

.nt-seat--target {
  box-shadow: 0 0 0 6rpx var(--camp-wolf), 0 0 28rpx 8rpx rgba(255, 116, 104, 0.45);
}

.nt-seat__sheriff {
  position: absolute;
  right: -8rpx;
  bottom: 4rpx;
  width: 32rpx;
  height: 32rpx;
  border-radius: 16rpx;
  border: 3rpx solid var(--ink);
  background: var(--gold);
  color: var(--ink);
  font-size: 18rpx;
  line-height: 32rpx;
  text-align: center;
}

.nt-game__head {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
  padding: 36rpx var(--page-gutter) 20rpx;
}

.nt-game__title {
  flex: 1;
  font-size: 36rpx;
  font-weight: 900;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nt-game__clock {
  font-size: 40rpx;
  font-weight: 900;
  color: var(--moon);
}

.nt-game__clock--warn {
  color: var(--camp-wolf);
}

.nt-game__step {
  font-size: 22rpx;
  color: var(--text-3);
}

/* 面板区高度整局固定（座位不跳）；内容放不下时在面板里滚，按钮贴底 */
.nt-game__area {
  box-sizing: border-box;
  overflow: hidden;
}

.nt-game__panel {
  height: 100%;
  box-sizing: border-box;
  overflow-y: auto;
  padding: 24rpx 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.nt-game__label {
  font-size: 24rpx;
  color: var(--text-3);
}

.nt-game__lead {
  font-size: 30rpx;
  font-weight: 700;
  color: var(--text);
}

.nt-game__hint {
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--text-2);
}

.nt-game__buttons {
  margin-top: auto;
  display: flex;
  gap: 16rpx;
}

.nt-game__button {
  flex: 1;
}

/* 中间卡片：场景图打底加暗色蒙层，内容竖向居中 */
.nt-scene__bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  opacity: 0.35;
}

.nt-scene__body {
  position: relative;
  height: 100%;
  box-sizing: border-box;
  padding: 32rpx 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20rpx;
  text-align: center;
  background: linear-gradient(180deg, rgba(7, 6, 10, 0.2) 0%, rgba(7, 6, 10, 0.75) 100%);
}

.nt-scene__icon {
  font-size: 72rpx;
  color: var(--gold);
  line-height: 1;
}

.nt-scene__kicker {
  font-size: 24rpx;
  letter-spacing: 4rpx;
  color: var(--text-2);
}

.nt-scene__title {
  font-family: var(--serif);
  font-size: 44rpx;
  font-weight: 900;
  line-height: 1.3;
  color: var(--text);
}

.nt-scene__text {
  font-size: 26rpx;
  line-height: 1.6;
  color: var(--text-soft);
}

.nt-scene__big {
  font-family: var(--serif);
  font-size: 96rpx;
  font-weight: 900;
  line-height: 1;
  color: var(--gold);
}

.nt-scene__avatar {
  width: 220rpx;
  height: 220rpx;
}

.nt-scene__avatar-text {
  font-size: 80rpx;
}
</style>
