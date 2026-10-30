<script setup>
// 大厅：房号与邀请、座位、本局规则（房主可改）、底部主按钮。
// 视图里不嵌任何自定义组件；提交都 emit('act') 交给房间页（统一处理 4002 和报错）。
// 规则只显示快照里的值，房主改了等接口返回新快照再变，失败自然就是原值。
import { computed } from 'vue'
import { lobbyAction, lobbySeats, rulesRows } from '../../../utils/judge/lobby.js'
import { stepRule } from '../../../utils/judge/create-room.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  board: { type: Object, default: null },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['act'])

const seats = computed(() => lobbySeats(props.snapshot))
const action = computed(() => lobbyAction(props.snapshot))
const rows = computed(() => rulesRows(props.snapshot))
const modeText = computed(() => {
  if (props.snapshot.judgeMode === 'joined') return props.snapshot.host ? '无法官 · 你也拿牌，手机当法官' : '无法官 · 房主手机当法官'
  return props.snapshot.judgeViewer ? '有法官 · 你是法官，不入座' : '有法官 · 法官不入座'
})

function currentInput() {
  const rules = props.snapshot.rules || {}
  return {
    sheriffEnabled: rules.sheriffDefault !== false,
    speechSeconds: rules.speechSeconds,
    sheriffSpeechSeconds: rules.sheriffSpeechSeconds,
    wolfSeconds: rules.wolfSeconds,
    roleSeconds: rules.roleSeconds,
  }
}

function changeRule(row, direction) {
  if (!row.editable || props.busy) return
  const input = currentInput()
  if (row.key === 'sheriffEnabled') {
    input.sheriffEnabled = !input.sheriffEnabled
  } else {
    const next = stepRule(input[row.key], row.key, direction)
    if (next === input[row.key]) return
    input[row.key] = next
  }
  emit('act', { kind: 'rules', rules: input })
}

function tapMain() {
  if (action.value.disabled || props.busy) return
  emit('act', { kind: action.value.kind })
}

function copyRoomCode() {
  const value = props.snapshot.roomCode || props.snapshot.roomId
  if (value) uni.setClipboardData({ data: String(value) })
}
</script>

<template>
  <view>
    <view class="nt-gutter">
      <view class="nt-ink jr-room">
        <view class="jr-room__banner">
          <image class="jr-room__bg" :src="board?.backgroundImage || '/static/scenes/tavern.webp'" mode="aspectFill" webp />
        </view>
        <view class="jr-room__body">
          <view class="jr-room__code-row">
            <view class="jr-room__code">
              <text class="nt-meta">房号</text>
              <text class="jr-room__digits serif">{{ snapshot.roomCode || '—' }}</text>
            </view>
            <view class="nt-btn nt-btn--ghost nt-btn--small" @tap="copyRoomCode">复制</view>
          </view>
          <view class="jr-room__board">
            <view class="jr-stack">
              <view
                v-for="(role, index) in (board?.cardRoles || []).slice(0, 5)"
                :key="role.roleId"
                class="nt-token jr-stack__token"
                :style="{ marginLeft: index ? '-16rpx' : '0', zIndex: 6 - index }"
              >
                <image v-if="role.portrait" class="nt-token__img" :src="role.portrait" mode="aspectFill" :style="{ borderColor: role.accent }" webp />
                <view v-else class="nt-token__text jr-text--sm" :style="{ borderColor: role.accent }">{{ role.name.slice(0, 1) }}</view>
              </view>
            </view>
            <view class="jr-room__board-text">
              <text class="jr-room__board-name">{{ snapshot.boardName }} · {{ snapshot.playerCount }} 人</text>
              <text class="jr-room__board-meta">{{ modeText }}</text>
            </view>
          </view>
          <button class="nt-btn nt-btn--cream nt-btn--block jr-share" open-type="share">邀请微信好友入座</button>
        </view>
      </view>

      <view class="jr-section">
        <text class="nt-h2">座位</text>
        <text class="nt-meta">{{ snapshot.joinedCount }} / {{ snapshot.playerCount }} 已入座 · {{ snapshot.readyCount }} 人已准备</text>
      </view>
      <view class="jr-seats">
        <view
          v-for="seat in seats"
          :key="seat.seatNo"
          class="jr-seat"
          :class="{ 'jr-seat--empty': seat.empty, 'jr-seat--self': seat.me }"
        >
          <text class="jr-seat__no serif" :class="{ 'jr-seat__no--empty': seat.empty }">{{ seat.seatNo }}</text>
          <block v-if="!seat.empty">
            <view class="nt-token jl-avatar">
              <image v-if="seat.avatar" class="nt-token__img" :src="seat.avatar" mode="aspectFill" />
              <view v-else class="nt-token__text jr-text--sm">{{ seat.nickname.slice(0, 1) }}</view>
            </view>
            <text class="jr-seat__name">{{ seat.nickname }}</text>
            <text class="jr-seat__state" :class="{ 'jr-seat__state--ready': seat.ready }">{{ seat.ready ? '已准备' : '未准备' }}</text>
          </block>
          <text v-else class="jr-seat__empty">空位</text>
        </view>
      </view>

      <view class="jr-section">
        <text class="nt-h2">本局规则</text>
        <text class="nt-meta">{{ snapshot.host ? '开局前可以改' : '房主设置' }}</text>
      </view>
      <view class="nt-panel jl-rules">
        <view v-for="row in rows" :key="row.key" class="jl-rule">
          <text class="jl-rule__label">{{ row.label }}</text>
          <view v-if="row.editable && row.key === 'sheriffEnabled'" class="jl-switch" :class="{ 'jl-switch--on': row.value === '开' }" @tap="changeRule(row, 1)">
            <view class="jl-switch__dot" :class="{ 'jl-switch__dot--on': row.value === '开' }" />
          </view>
          <view v-else-if="row.editable" class="jl-stepper">
            <view class="jl-stepper__btn" @tap="changeRule(row, -1)">-</view>
            <text class="jl-rule__value">{{ row.value }}</text>
            <view class="jl-stepper__btn" @tap="changeRule(row, 1)">+</view>
          </view>
          <text v-else class="jl-rule__value">{{ row.value }}</text>
        </view>
      </view>

      <view class="jr-tip">
        <text class="jr-tip__icon">◉</text>
        <text class="jr-tip__text">开局后系统按顺序叫每个角色，大家闭眼用自己的手机操作；夜里所有手机画面一样，记得调成静音。</text>
      </view>
    </view>

    <view class="nt-bar">
      <view
        class="nt-btn jr-cta"
        :class="[action.kind === 'unready' || action.disabled ? 'nt-btn--ghost' : 'nt-btn--gold', { 'nt-btn--disabled': action.disabled || busy }]"
        @tap="tapMain"
      >
        {{ action.label }}
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
/* ---------- 等待入座 ---------- */
.jr-room {
  margin-top: 16rpx;
  overflow: hidden;
  border-radius: 40rpx;
}

.jr-room__banner {
  position: relative;
  height: 240rpx;
  border-bottom: 4rpx solid var(--ink);
  overflow: hidden;
  background: var(--panel-sunk);
}

.jr-room__bg {
  width: 100%;
  height: 100%;
}

.jr-room__night {
  position: absolute;
  right: -40rpx;
  top: -60rpx;
  width: 260rpx;
  height: 260rpx;
  border-radius: 50%;
  background: var(--panel-raised);
}

.jr-room__body {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jr-room__code-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.jr-room__code {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jr-room__digits {
  color: var(--gold);
  font-size: 80rpx;
  font-weight: 900;
  letter-spacing: 12rpx;
  line-height: 1.1;
}

.jr-room__board {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding-top: 24rpx;
  border-top: 2rpx solid var(--panel-line);
}

.jr-room__board-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.jr-room__board-name {
  font-size: 30rpx;
  font-weight: 700;
}

.jr-stack {
  display: flex;
  padding-left: 4rpx;
  flex-shrink: 0;
}

.jr-stack__token {
  position: relative;
  width: 64rpx;
  height: 64rpx;
}

.jr-text--sm {
  font-size: 26rpx;
}

.jr-text--md {
  font-size: 30rpx;
}

.jr-share {
  margin: 0;
  line-height: normal;
}

.jr-share::after {
  border: none;
}

.jr-section {
  margin: 44rpx 0 20rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20rpx;
}

.jr-seats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20rpx;
}

.jr-seat {
  position: relative;
  min-height: 224rpx;
  padding: 20rpx 12rpx;
  border-radius: 28rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  box-sizing: border-box;
}

.jr-seat--self {
  border-color: var(--moon);
}

.jr-seat--empty {
  background: transparent;
  border: 3rpx dashed var(--line-strong);
  box-shadow: none;
}

.jr-seat__no {
  position: absolute;
  z-index: 2;
  left: 14rpx;
  top: 10rpx;
  color: var(--gold);
  font-size: 26rpx;
  font-weight: 900;
}

.jr-seat--empty .jr-seat__no {
  position: static;
  color: var(--text-4);
  font-size: 32rpx;
}

.jr-back {
  position: relative;
  width: 80rpx;
  height: 104rpx;
  border-radius: 14rpx;
  background: var(--panel);
  border: 4rpx solid var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.jr-back__line {
  position: absolute;
  left: 8rpx;
  top: 8rpx;
  right: 8rpx;
  bottom: 8rpx;
  border: 2rpx solid var(--gold);
  border-radius: 8rpx;
  opacity: 0.55;
}

.jr-back__moon {
  color: var(--gold);
  font-size: 30rpx;
}

.jr-seat__name {
  max-width: 100%;
  font-size: 26rpx;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jr-seat__state {
  color: var(--text-3);
  font-size: 22rpx;
}

.jr-seat__state--ready {
  color: var(--camp-villager);
  font-weight: 700;
}

.jr-seat__empty {
  color: var(--text-3);
  font-size: 24rpx;
}

.jr-room__board-meta {
  color: var(--text-2);
  font-size: 24rpx;
  white-space: nowrap;
}

.jr-tip {
  margin-top: 36rpx;
  padding: 28rpx;
  border-radius: 28rpx;
  background: var(--panel-raised);
  display: flex;
  gap: 20rpx;
}

.jr-tip__icon {
  color: var(--moon);
  font-size: 32rpx;
}

.jr-tip__text {
  flex: 1;
  color: var(--text-soft);
  font-size: 26rpx;
  line-height: 1.7;
}

.jr-cta {
  flex: 1;
}


.jr-seat__no--empty {
  opacity: 0.5;
}

.jl-avatar {
  width: 72rpx;
  height: 72rpx;
}

.jl-rules {
  padding: 8rpx 28rpx;
}

.jl-rule {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 84rpx;
}

.jl-rule__label {
  font-size: 28rpx;
  color: var(--text);
}

.jl-rule__value {
  min-width: 112rpx;
  text-align: center;
  font-size: 28rpx;
  color: var(--text-soft);
}

.jl-switch {
  position: relative;
  width: 88rpx;
  height: 48rpx;
  border-radius: 24rpx;
  background: var(--line);
}

.jl-switch--on {
  background: var(--moon);
}

.jl-switch__dot {
  position: absolute;
  top: 6rpx;
  left: 6rpx;
  width: 36rpx;
  height: 36rpx;
  border-radius: 18rpx;
  background: var(--text);
}

.jl-switch__dot--on {
  left: 46rpx;
}

.jl-stepper {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.jl-stepper__btn {
  width: 56rpx;
  height: 56rpx;
  border-radius: 16rpx;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 32rpx;
  line-height: 56rpx;
  text-align: center;
}
</style>
