<script setup>
// 出局待办：当事人看到技能询问 / 开枪选人 / 移交警徽；其他人只看到「等待出局玩家确认」，法官多一行是谁、什么待办。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { pendingPanel } from '../../../utils/judge/pending.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['act'])

const selection = ref(0)
const pendingKey = computed(() => (props.snapshot.pending ? `${props.snapshot.pending.kind}-${props.snapshot.pending.seatNo}` : ''))
watch(pendingKey, () => {
  selection.value = 0
})

const panel = computed(() => pendingPanel(props.snapshot, selection.value))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.pending?.deadline || props.snapshot.phaseDeadline, props.now, props.snapshot.paused))

function pick(seat) {
  selection.value = selection.value === seat ? 0 : seat
}

function tapButton(button) {
  if (button.disabled || props.busy) return
  emit('act', { kind: 'skill', decision: button.decision, targetSeatNo: button.target || null })
}
</script>

<template>
  <view class="nt-gutter jp">
    <view class="jp-head">
      <text class="jp-head__title serif">{{ title }}</text>
      <text class="jp-head__clock" :class="{ 'jp-head__clock--warn': clock.warn }">{{ clock.text }}</text>
    </view>

    <view v-if="panel.kind === 'waiting'" class="nt-panel jp-wait">
      <text class="jp-wait__moon">☾</text>
      <text class="jp-wait__title">{{ panel.title }}</text>
      <text v-if="panel.detail" class="jp-wait__detail">{{ panel.detail }}</text>
    </view>

    <view v-else class="nt-ink jp-card">
      <text class="jp-card__title serif">{{ panel.title }}</text>
      <view v-if="panel.needsTarget" class="jp-targets">
        <view
          v-for="seat in panel.targets"
          :key="seat"
          class="jp-target"
          :class="{ 'jp-target--on': selection === seat }"
          @tap="pick(seat)"
        >
          <text class="jp-target__no serif">{{ seat }}</text>
        </view>
      </view>
      <view class="jp-buttons">
        <view
          v-for="button in panel.buttons"
          :key="button.decision"
          class="nt-btn jp-button"
          :class="[button.decision === 'use' ? 'nt-btn--gold' : 'nt-btn--ghost', { 'nt-btn--disabled': button.disabled || busy }]"
          @tap="tapButton(button)"
        >{{ button.label }}</view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jp {
  padding-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jp-head {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
}

.jp-head__title {
  flex: 1;
  font-size: 36rpx;
  font-weight: 900;
  color: var(--text);
}

.jp-head__clock {
  font-size: 40rpx;
  font-weight: 900;
  color: var(--moon);
}

.jp-head__clock--warn {
  color: var(--camp-wolf);
}

.jp-wait {
  padding: 64rpx 32rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
}

.jp-wait__moon {
  color: var(--gold);
  font-size: 80rpx;
}

.jp-wait__title {
  color: var(--text);
  font-size: 32rpx;
  font-weight: 700;
}

.jp-wait__detail {
  color: var(--text-2);
  font-size: 26rpx;
}

.jp-card {
  padding: 36rpx 32rpx;
  border-radius: 36rpx;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jp-card__title {
  color: var(--text);
  font-size: 38rpx;
  font-weight: 900;
}

.jp-targets {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.jp-target {
  width: 96rpx;
  height: 96rpx;
  border-radius: 48rpx;
  background: var(--panel-raised);
  border: 4rpx solid transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.jp-target--on {
  border-color: var(--moon);
}

.jp-target__no {
  color: var(--gold);
  font-size: 36rpx;
  font-weight: 900;
}

.jp-buttons {
  display: flex;
  gap: 16rpx;
}

.jp-button {
  flex: 1;
}
</style>
