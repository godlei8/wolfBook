<script setup>
// 放逐投票 / PK 投票（进度只显示投了几人）和票型公布（加权票数、投票人、出局 / 平票 / 白痴翻牌）。
// 房主的「结算 / 继续」在房间页底部的操作栏。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { voteResultPanel, votePanel } from '../../../utils/judge/day.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['act'])

const selection = ref(0)
watch(() => `${props.snapshot.currentDay}-${props.snapshot.currentPhase}`, () => {
  selection.value = 0
})

const result = computed(() => props.snapshot.currentPhase === 'vote_result')
const panel = computed(() => votePanel(props.snapshot))
const outcome = computed(() => voteResultPanel(props.snapshot))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const verdict = computed(() => {
  const value = outcome.value
  if (value.revealedIdiot) return `${value.revealedIdiot} 号翻牌是白痴，免于出局`
  if (value.eliminatedSeat) return `${value.eliminatedSeat} 号被放逐`
  if (value.pkSeats.length) return `${value.pkSeats.join('、')} 号平票，进入 PK`
  return '没有人出局'
})

function pick(seat) {
  if (!panel.value.canVote) return
  selection.value = selection.value === seat ? 0 : seat
}

function vote(targetSeatNo) {
  if (props.busy) return
  emit('act', { kind: 'vote', targetSeatNo })
}
</script>

<template>
  <view class="nt-gutter jv">
    <view class="jv-head">
      <text class="jv-head__title serif">{{ title }}</text>
      <text v-if="!result" class="jv-head__clock" :class="{ 'jv-head__clock--warn': clock.warn }">{{ clock.text }}</text>
    </view>

    <block v-if="!result">
      <view class="jv-targets">
        <view
          v-for="seat in panel.targets"
          :key="seat"
          class="jv-target"
          :class="{ 'jv-target--on': selection === seat, 'jv-target--off': !panel.canVote }"
          @tap="pick(seat)"
        >
          <text class="jv-target__no serif">{{ seat }}</text>
        </view>
      </view>
      <view class="nt-panel jv-panel">
        <text class="jv-panel__title">{{ panel.stage === 'pk' ? 'PK 投票：只能投平票的人' : '投出你怀疑的人' }}</text>
        <text class="jv-panel__hint">{{ panel.progress }}{{ panel.mine ? ` · ${panel.mine}` : '' }}</text>
        <view v-if="panel.canVote" class="jv-buttons">
          <view class="nt-btn nt-btn--gold jv-button" :class="{ 'nt-btn--disabled': !selection || busy }" @tap="selection && vote(selection)">
            {{ selection ? `投给 ${selection} 号` : '先点一个座位' }}
          </view>
          <view class="nt-btn nt-btn--ghost jv-button" :class="{ 'nt-btn--disabled': busy }" @tap="vote(null)">弃票</view>
        </view>
        <text v-else class="jv-panel__hint">{{ panel.reason }}</text>
      </view>
    </block>

    <block v-else>
      <view class="nt-ink jv-result">
        <text class="jv-result__verdict serif">{{ verdict }}</text>
        <view v-for="tally in outcome.tallies" :key="tally.targetSeatNo" class="jv-tally">
          <text class="jv-tally__target">{{ tally.targetSeatNo }} 号</text>
          <text class="jv-tally__weight">{{ tally.weight }} 票</text>
          <text class="jv-tally__voters">← {{ tally.voters }}</text>
        </view>
        <text v-if="outcome.abstain" class="jv-tally__abstain">弃票：{{ outcome.abstain }}</text>
      </view>
    </block>
  </view>
</template>

<style scoped lang="scss">
.jv {
  padding-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jv-head {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
}

.jv-head__title {
  flex: 1;
  font-size: 36rpx;
  font-weight: 900;
  color: var(--text);
}

.jv-head__clock {
  font-size: 40rpx;
  font-weight: 900;
  color: var(--moon);
}

.jv-head__clock--warn {
  color: var(--camp-wolf);
}

.jv-targets {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.jv-target {
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

.jv-target--on {
  border-color: var(--moon);
}

.jv-target--off {
  opacity: 0.6;
}

.jv-target__no {
  color: var(--gold);
  font-size: 36rpx;
  font-weight: 900;
}

.jv-panel {
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.jv-panel__title {
  color: var(--text);
  font-size: 30rpx;
  font-weight: 900;
}

.jv-panel__hint {
  color: var(--text-2);
  font-size: 24rpx;
}

.jv-buttons {
  display: flex;
  gap: 16rpx;
}

.jv-button {
  flex: 1;
}

.jv-result {
  padding: 36rpx 32rpx;
  border-radius: 36rpx;
  background: var(--panel);
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.jv-result__verdict {
  color: var(--text);
  font-size: 38rpx;
  font-weight: 900;
}

.jv-tally {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.jv-tally__target {
  width: 96rpx;
  color: var(--gold);
  font-size: 30rpx;
  font-weight: 900;
}

.jv-tally__weight {
  width: 96rpx;
  color: var(--text);
  font-size: 26rpx;
}

.jv-tally__voters {
  flex: 1;
  color: var(--text-2);
  font-size: 24rpx;
}

.jv-tally__abstain {
  color: var(--text-3);
  font-size: 24rpx;
}
</style>
