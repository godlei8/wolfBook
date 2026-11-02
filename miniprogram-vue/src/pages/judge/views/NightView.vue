<script setup>
// 夜晚：所有人同一张牌桌（两侧座位、居中当前角色立绘、标题和倒计时），
// 这一步的持有者在立绘下是操作面板；其他人同一位置是同样大小的「天黑请闭眼」面板，画面结构不变（零场外）。
// 不振动、不弹「轮到你」、不出声；提交成功也不弹提示，只更新面板。
// 牌桌（座位、样式）和其他阶段共用 utils/judge/stage.js 与 App.vue 的 nt-stage / nt-seat / nt-game 类，
// 座位标记那段模板每个视图各写一份（自定义组件里不再嵌组件）。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { isStepHolder, nightArt, nightPanel } from '../../../utils/judge/night-panel.js'
import { tonightRows } from '../../../utils/judge/judge-bar.js'
import { gameAreaStyle, stageLayout } from '../../../utils/judge/stage.js'
import { useStageFit } from '../../../utils/judge/stage-fit.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  board: { type: Object, default: null },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
  // 房间页记住的牌桌高度（rpx）和底部有没有固定操作栏
  stageHeight: { type: Number, default: 0 },
  bar: { type: Boolean, default: false },
})
const emit = defineEmits(['act', 'fit'])

const selection = ref(0)
const stepKey = computed(() => `${props.snapshot.currentDay}-${props.snapshot.currentStep?.index ?? -1}`)
watch(stepKey, () => {
  selection.value = 0
})

const layout = computed(() => stageLayout(props.snapshot, 'night', { selection: selection.value, stageHeight: props.stageHeight }))
const holder = computed(() => isStepHolder(props.snapshot))
const panel = computed(() => nightPanel(props.snapshot, selection.value))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const art = computed(() => nightArt(props.snapshot, props.board))
// 法官的「今晚」汇总（法官手机不在零场外的约束里，大家都知道谁是法官）
const rows = computed(() => tonightRows(props.snapshot, props.board))
useStageFit(props, emit, () => layout.value.cardHeight)

function seatClass(seat) {
  return { 'nt-seat--dim': seat.dim, 'nt-seat--on': seat.selected, 'nt-seat--speaking': seat.speaking, 'nt-seat--out': seat.out }
}

function tapSeat(seat) {
  if (!holder.value || !seat.tappable) return
  selection.value = selection.value === seat.seatNo ? 0 : seat.seatNo
}

function tapButton(button) {
  if (button.disabled || props.busy) return
  emit('act', { kind: 'night', actionType: button.action, targetSeatNo: button.target || null })
}
</script>

<template>
  <view class="nt-game">
    <view class="nt-stage">
      <view v-for="side in layout.sides" :key="side.key" class="nt-stage__col" :class="`nt-stage__col--${side.key}`" :style="{ gap: `${layout.gap}rpx` }">
        <view
          v-for="seat in side.seats"
          :key="seat.seatNo"
          class="nt-seat"
          :class="seatClass(seat)"
          :style="{ width: `${layout.size}rpx`, height: `${layout.size}rpx` }"
          @tap="tapSeat(seat)"
        >
          <view class="nt-token nt-seat__token" :class="{ 'nt-token--dead': seat.dead }">
            <image v-if="seat.avatar" class="nt-token__img" :src="seat.avatar" mode="aspectFill" />
            <view v-else class="nt-token__text nt-seat__text">{{ seat.initial }}</view>
          </view>
          <text class="nt-seat__no">{{ seat.seatNo }}</text>
          <text v-if="seat.role" class="nt-seat__role" :style="{ backgroundColor: seat.roleColor }">{{ seat.role }}</text>
          <view v-if="seat.mate" class="nt-seat__mate" />
          <text v-if="seat.tag" class="nt-seat__tag" :class="seat.tagKind ? `nt-seat__tag--${seat.tagKind}` : ''">{{ seat.tag }}</text>
          <text v-if="seat.sheriff" class="nt-seat__sheriff">★</text>
        </view>
      </view>

      <view class="nt-stage__center nt-card" :style="{ height: `${layout.cardHeight}rpx` }">
        <image v-if="art" class="jn-art__img" :src="art" mode="aspectFill" webp />
        <view v-else class="nt-card__back">
          <view class="nt-card__line" />
          <text class="nt-card__moon">☾</text>
        </view>
      </view>
    </view>

    <view class="nt-game__head">
      <text class="nt-game__title serif">{{ title }}</text>
      <text class="nt-game__clock" :class="{ 'nt-game__clock--warn': clock.warn }">{{ clock.text }}</text>
      <text class="nt-game__step">{{ snapshot.currentStep ? `${snapshot.currentStep.index + 1} / ${snapshot.currentStep.total}` : '' }}</text>
    </view>

    <view class="nt-gutter nt-game__area" :style="gameAreaStyle(bar)">
      <view v-if="snapshot.judgeViewer" class="nt-panel nt-game__panel jn-tonight">
        <text class="jn-panel__label">今晚</text>
        <view v-for="row in rows" :key="row.role" class="jn-tonight__row">
          <text class="jn-tonight__role">{{ row.role }}</text>
          <text class="jn-tonight__seats">{{ row.seats.join(' ') }} 号</text>
          <text class="jn-tonight__state">{{ row.state }}</text>
        </view>
      </view>
      <view v-else-if="panel" class="nt-panel nt-game__panel jn-panel">
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
      <view v-else class="nt-panel nt-game__panel jn-rest">
        <text class="jn-rest__moon">☾</text>
        <text class="jn-rest__text">天黑请闭眼</text>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jn-art__img {
  width: 100%;
  height: 100%;
  display: block;
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

.jn-rest {
  align-items: center;
  justify-content: center;
}

.jn-rest__moon {
  font-size: 56rpx;
  color: var(--gold);
  opacity: 0.6;
}

.jn-rest__text {
  font-size: 26rpx;
  color: var(--text-3);
  letter-spacing: 4rpx;
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
