<script setup>
// 夜晚：所有人同一张立绘屏（两侧座位、居中当前角色立绘、标题和倒计时），
// 这一步的持有者在立绘下多一块操作面板；其他人同一位置是等高的空白占位，画面结构不变（零场外）。
// 不振动、不弹「轮到你」、不出声；提交成功也不弹提示，只更新面板。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle, seatColumns } from '../../../utils/judge/room-view.js'
import { isStepHolder, nightArt, nightPanel, panelHeight, seatBadges } from '../../../utils/judge/night-panel.js'
import { godSeats, tonightRows } from '../../../utils/judge/judge-bar.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  board: { type: Object, default: null },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['act'])

const selection = ref(0)
const stepKey = computed(() => `${props.snapshot.currentDay}-${props.snapshot.currentStep?.index ?? -1}`)
watch(stepKey, () => {
  selection.value = 0
})

const columns = computed(() => seatColumns(props.snapshot.players, props.snapshot.playerCount))
const badges = computed(() => seatBadges(props.snapshot, selection.value))
const holder = computed(() => isStepHolder(props.snapshot))
const panel = computed(() => nightPanel(props.snapshot, selection.value))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const art = computed(() => nightArt(props.snapshot, props.board))
const areaHeight = computed(() => panelHeight(props.snapshot.currentStep?.kind))
// 法官的上帝视角：座位上的身份角标、今晚汇总（法官手机不在零场外的约束里，大家都知道谁是法官）
const god = computed(() => godSeats(props.snapshot))
const rows = computed(() => tonightRows(props.snapshot, props.board))

function godOf(seatNo) {
  return god.value.get(seatNo) || null
}

function badgeOf(seatNo) {
  return badges.value.get(seatNo) || { mate: false, votes: 0, selected: false, disabled: true, dead: false }
}

function tapSeat(seat) {
  if (!holder.value || seat.empty || badgeOf(seat.seatNo).disabled) return
  selection.value = selection.value === seat.seatNo ? 0 : seat.seatNo
}

function tapButton(button) {
  if (button.disabled || props.busy) return
  emit('act', { kind: 'night', actionType: button.action, targetSeatNo: button.target || null })
}
</script>

<template>
  <view class="jn">
    <view class="jn-stage">
      <view class="jn-col" :style="{ gap: `${columns.gap}rpx` }">
        <view
          v-for="seat in columns.left"
          :key="seat.seatNo"
          class="jn-seat"
          :class="{ 'jn-seat--on': badgeOf(seat.seatNo).selected }"
          :style="{ width: `${columns.size}rpx`, height: `${columns.size}rpx` }"
          @tap="tapSeat(seat)"
        >
          <view class="nt-token jn-token" :class="{ 'nt-token--dead': badgeOf(seat.seatNo).dead }">
            <image v-if="seat.player && seat.player.avatar" class="nt-token__img" :src="seat.player.avatar" mode="aspectFill" />
            <view v-else class="nt-token__text jn-token__text">{{ seat.player ? (seat.player.nickname || '').slice(0, 1) : '' }}</view>
          </view>
          <text class="jn-seat__no">{{ seat.seatNo }}</text>
          <text v-if="godOf(seat.seatNo)" class="jn-seat__role" :style="{ backgroundColor: godOf(seat.seatNo).roleColor }">{{ godOf(seat.seatNo).roleShort }}</text>
          <view v-if="badgeOf(seat.seatNo).mate" class="jn-seat__mate" />
          <text v-if="badgeOf(seat.seatNo).votes || (godOf(seat.seatNo) && godOf(seat.seatNo).votes)" class="jn-seat__votes">{{ badgeOf(seat.seatNo).votes || godOf(seat.seatNo).votes }}</text>
        </view>
      </view>

      <view class="jn-art nt-card" :style="{ height: `${columns.cardHeight}rpx` }">
        <image v-if="art" class="jn-art__img" :src="art" mode="aspectFill" webp />
        <view v-else class="nt-card__back">
          <view class="nt-card__line" />
          <text class="nt-card__moon">☾</text>
        </view>
      </view>

      <view class="jn-col" :style="{ gap: `${columns.gap}rpx` }">
        <view
          v-for="seat in columns.right"
          :key="seat.seatNo"
          class="jn-seat"
          :class="{ 'jn-seat--on': badgeOf(seat.seatNo).selected }"
          :style="{ width: `${columns.size}rpx`, height: `${columns.size}rpx` }"
          @tap="tapSeat(seat)"
        >
          <view class="nt-token jn-token" :class="{ 'nt-token--dead': badgeOf(seat.seatNo).dead }">
            <image v-if="seat.player && seat.player.avatar" class="nt-token__img" :src="seat.player.avatar" mode="aspectFill" />
            <view v-else class="nt-token__text jn-token__text">{{ seat.player ? (seat.player.nickname || '').slice(0, 1) : '' }}</view>
          </view>
          <text class="jn-seat__no">{{ seat.seatNo }}</text>
          <text v-if="godOf(seat.seatNo)" class="jn-seat__role" :style="{ backgroundColor: godOf(seat.seatNo).roleColor }">{{ godOf(seat.seatNo).roleShort }}</text>
          <view v-if="badgeOf(seat.seatNo).mate" class="jn-seat__mate" />
          <text v-if="badgeOf(seat.seatNo).votes || (godOf(seat.seatNo) && godOf(seat.seatNo).votes)" class="jn-seat__votes">{{ badgeOf(seat.seatNo).votes || godOf(seat.seatNo).votes }}</text>
        </view>
      </view>
    </view>

    <view class="jn-head">
      <text class="jn-head__title serif">{{ title }}</text>
      <text class="jn-head__clock" :class="{ 'jn-head__clock--warn': clock.warn }">{{ clock.text }}</text>
      <text class="jn-head__step">{{ snapshot.currentStep ? `${snapshot.currentStep.index + 1} / ${snapshot.currentStep.total}` : '' }}</text>
    </view>

    <view v-if="snapshot.judgeViewer" class="nt-gutter">
      <view class="nt-panel jn-panel jn-tonight">
        <text class="jn-panel__label">今晚</text>
        <view v-for="row in rows" :key="row.role" class="jn-tonight__row">
          <text class="jn-tonight__role">{{ row.role }}</text>
          <text class="jn-tonight__seats">{{ row.seats.join(' ') }} 号</text>
          <text class="jn-tonight__state">{{ row.state }}</text>
        </view>
      </view>
    </view>
    <view v-else class="jn-area nt-gutter" :style="{ height: `${areaHeight}rpx` }">
      <view v-if="panel" class="nt-panel jn-panel">
        <block v-if="panel.kind === 'wolf'">
          <view class="jn-panel__row">
            <text class="jn-panel__label">刀口</text>
            <text class="jn-panel__value jn-panel__value--wolf">{{ panel.knife.text }}</text>
          </view>
          <view class="jn-picks">
            <text v-for="row in panel.rows" :key="row.seatNo" class="jn-pick" :class="{ 'jn-pick--me': row.me }">{{ row.seatNo }} 号 → {{ row.text }}</text>
          </view>
        </block>
        <block v-else-if="panel.kind === 'witch'">
          <view class="jn-panel__row">
            <text class="jn-panel__label">今晚</text>
            <text class="jn-panel__value">{{ panel.knifeText }}</text>
          </view>
          <view class="jn-potions">
            <text class="jn-potion" :class="{ 'jn-potion--off': !panel.antidote.left }">解药 {{ panel.antidote.left }}</text>
            <text class="jn-potion" :class="{ 'jn-potion--off': !panel.poison.left }">毒药 {{ panel.poison.left }}</text>
          </view>
        </block>
        <block v-else-if="panel.kind === 'seer'">
          <view class="jn-panel__row">
            <text class="jn-panel__label">查验</text>
            <text class="jn-panel__value">{{ panel.result || '点一个座位，再点查验' }}</text>
          </view>
        </block>
        <block v-else-if="panel.kind === 'guard'">
          <view class="jn-panel__row">
            <text class="jn-panel__label">守护</text>
            <text class="jn-panel__value">{{ panel.lastText || '点一个座位，再点守护' }}</text>
          </view>
        </block>
        <block v-else>
          <view class="jn-panel__row">
            <text class="jn-panel__value">{{ snapshot.currentStep ? snapshot.currentStep.line : '' }}</text>
          </view>
        </block>
        <text v-if="panel.done" class="jn-panel__done">{{ panel.done }}</text>
        <view class="jn-buttons">
          <view
            v-for="button in panel.buttons"
            :key="button.action"
            class="nt-btn nt-btn--small jn-button"
            :class="[button.action === 'none' || button.action === 'confirm' ? 'nt-btn--ghost' : 'nt-btn--gold', { 'nt-btn--disabled': button.disabled || busy }]"
            @tap="tapButton(button)"
          >{{ button.label }}</view>
        </view>
      </view>
      <view v-else class="jn-panel jn-panel--blank" />
    </view>
  </view>
</template>

<style scoped lang="scss">
.jn-stage {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
  padding: 24rpx var(--page-gutter) 0;
}

.jn-col {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.jn-seat {
  position: relative;
  border-radius: 50%;
}

.jn-seat--on {
  box-shadow: 0 0 0 6rpx var(--moon);
}

.jn-token {
  width: 100%;
  height: 100%;
}

.jn-token__text {
  font-size: 28rpx;
}

.jn-seat__no {
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

.jn-seat__mate {
  position: absolute;
  right: 0;
  top: 0;
  width: 20rpx;
  height: 20rpx;
  border-radius: 10rpx;
  border: 3rpx solid var(--ink);
  background: var(--camp-wolf);
}

.jn-seat__role {
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

.jn-tonight {
  gap: 12rpx;
}

.jn-tonight__row {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.jn-tonight__role {
  width: 96rpx;
  flex-shrink: 0;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--text);
}

.jn-tonight__seats {
  width: 120rpx;
  flex-shrink: 0;
  font-size: 22rpx;
  color: var(--text-3);
}

.jn-tonight__state {
  flex: 1;
  font-size: 24rpx;
  color: var(--text-soft);
}

.jn-seat__votes {
  position: absolute;
  left: -8rpx;
  top: -8rpx;
  min-width: 32rpx;
  border-radius: 16rpx;
  border: 3rpx solid var(--ink);
  background: var(--camp-wolf);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 900;
  line-height: 28rpx;
  text-align: center;
}

.jn-art {
  position: relative;
  flex: 1;
  min-width: 0;
}

.jn-art__img {
  width: 100%;
  height: 100%;
  display: block;
}

.jn-head {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
  padding: 36rpx var(--page-gutter) 20rpx;
}

.jn-head__title {
  flex: 1;
  font-size: 36rpx;
  font-weight: 900;
  color: var(--text);
}

.jn-head__clock {
  font-size: 40rpx;
  font-weight: 900;
  color: var(--moon);
}

.jn-head__clock--warn {
  color: var(--camp-wolf);
}

.jn-head__step {
  font-size: 22rpx;
  color: var(--text-3);
}

/* 面板高度只看步骤类型（零场外）；内容放不下时在面板里滚，不撑出去也不裁掉按钮 */
.jn-area {
  box-sizing: border-box;
  overflow: hidden;
}

.jn-panel {
  height: 100%;
  box-sizing: border-box;
  overflow-y: auto;
  padding: 24rpx 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.jn-panel--blank {
  border-radius: 28rpx;
}

.jn-panel__row {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
}

.jn-panel__label {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--text-3);
}

.jn-panel__value {
  font-size: 30rpx;
  color: var(--text);
  font-weight: 700;
}

.jn-panel__value--wolf {
  color: var(--camp-wolf);
}

.jn-picks {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx 24rpx;
}

.jn-pick {
  font-size: 24rpx;
  color: var(--text-2);
}

.jn-pick--me {
  color: var(--text);
  font-weight: 700;
}

.jn-potions {
  display: flex;
  gap: 16rpx;
}

.jn-potion {
  padding: 8rpx 20rpx;
  border-radius: 16rpx;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 24rpx;
}

.jn-potion--off {
  opacity: 0.4;
}

.jn-panel__done {
  font-size: 24rpx;
  color: var(--moon);
}

.jn-buttons {
  margin-top: auto;
  display: flex;
  gap: 16rpx;
}

.jn-button {
  flex: 1;
}
</style>
