<script setup>
// 上警：报名（只看到自己报没报）→ 警上发言（警上的人能退水）→ 警长投票 / PK（没上警的人投）。
// 房主的推进按钮（截止报名、开始投票等）在房间页底部的操作栏。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { sheriffPanel } from '../../../utils/judge/sheriff.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['act'])

const selection = ref(0)
watch(() => props.snapshot.currentPhase, () => {
  selection.value = 0
})

const panel = computed(() => sheriffPanel(props.snapshot))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const seats = computed(() => (props.snapshot.players || []).filter((player) => player.seatNo).sort((a, b) => a.seatNo - b.seatNo))

function seatClass(player) {
  return {
    'jd-seat--dead': player.alive === false,
    'jd-seat--speaker': panel.value.speakerSeat === player.seatNo,
    'jd-seat--on': selection.value === player.seatNo,
    'jd-seat--target': panel.value.canVote && panel.value.targets.includes(player.seatNo),
  }
}

function tapSeat(player) {
  if (!panel.value.canVote || !panel.value.targets.includes(player.seatNo)) return
  selection.value = selection.value === player.seatNo ? 0 : player.seatNo
}

function send(payload) {
  if (props.busy) return
  emit('act', { kind: 'sheriff', payload })
}

function vote() {
  if (!selection.value) return
  send({ targetSeatNo: selection.value })
}
</script>

<template>
  <view class="nt-gutter jd">
    <view class="jd-head">
      <text class="jd-head__title serif">{{ title }}</text>
      <text class="jd-head__clock" :class="{ 'jd-head__clock--warn': clock.warn }">{{ clock.text }}</text>
    </view>

    <view class="jd-seats">
      <view v-for="player in seats" :key="player.seatNo" class="jd-seat" :class="seatClass(player)" @tap="tapSeat(player)">
        <view class="nt-token jd-token" :class="{ 'nt-token--dead': player.alive === false }">
          <image v-if="player.avatar" class="nt-token__img" :src="player.avatar" mode="aspectFill" />
          <view v-else class="nt-token__text jd-token__text">{{ (player.nickname || '').slice(0, 1) }}</view>
        </view>
        <text class="jd-seat__no">{{ player.seatNo }}</text>
        <text v-if="panel.candidates.includes(player.seatNo)" class="jd-seat__tag">警</text>
      </view>
    </view>

    <view class="nt-panel jd-panel">
      <block v-if="panel.stage === 'signup'">
        <text class="jd-panel__title">上警竞选警长</text>
        <text class="jd-panel__hint">警长有 1.5 票，并决定发言顺序。报名只有你自己看得到。</text>
        <view v-if="panel.canSignUp" class="jd-buttons">
          <view class="nt-btn jd-button" :class="[panel.candidate ? 'nt-btn--gold' : 'nt-btn--ghost', { 'nt-btn--disabled': busy }]" @tap="send({ candidate: true })">
            {{ panel.candidate ? '已上警' : '上警' }}
          </view>
          <view class="nt-btn jd-button" :class="[panel.candidate ? 'nt-btn--ghost' : 'nt-btn--gold', { 'nt-btn--disabled': busy }]" @tap="send({ candidate: false })">
            {{ panel.candidate ? '不上警了' : '不上警' }}
          </view>
        </view>
      </block>
      <block v-else-if="panel.stage === 'speech'">
        <text class="jd-panel__title">{{ panel.speakerSeat ? `${panel.speakerSeat} 号警上发言` : '警上发言' }}</text>
        <text class="jd-panel__hint">警上：{{ panel.candidates.join('、') || '无' }} 号</text>
        <view v-if="panel.canWithdraw" class="jd-buttons">
          <view class="nt-btn nt-btn--ghost jd-button" :class="{ 'nt-btn--disabled': busy }" @tap="send({ withdraw: true })">退水</view>
        </view>
      </block>
      <block v-else>
        <text class="jd-panel__title">{{ panel.stage === 'pk' ? '警长 PK 投票' : '警长投票' }}</text>
        <text class="jd-panel__hint">{{ panel.progress }}{{ panel.voted ? ` · 你投了 ${panel.myVote} 号` : '' }}</text>
        <view v-if="panel.canVote" class="jd-buttons">
          <view class="nt-btn nt-btn--gold jd-button" :class="{ 'nt-btn--disabled': !selection || busy }" @tap="vote">
            {{ selection ? `投给 ${selection} 号` : '点一个警上的座位' }}
          </view>
        </view>
        <text v-else class="jd-panel__hint">{{ panel.reason }}</text>
      </block>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jd {
  padding-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 28rpx;
}

.jd-head {
  display: flex;
  align-items: baseline;
  gap: 20rpx;
}

.jd-head__title {
  flex: 1;
  font-size: 36rpx;
  font-weight: 900;
  color: var(--text);
}

.jd-head__clock {
  font-size: 40rpx;
  font-weight: 900;
  color: var(--moon);
}

.jd-head__clock--warn {
  color: var(--camp-wolf);
}

.jd-seats {
  display: flex;
  flex-wrap: wrap;
  gap: 24rpx 20rpx;
}

.jd-seat {
  position: relative;
  width: 96rpx;
  height: 96rpx;
  border-radius: 48rpx;
}

.jd-seat--target {
  box-shadow: 0 0 0 4rpx var(--line);
}

.jd-seat--speaker {
  box-shadow: 0 0 0 6rpx var(--gold);
}

.jd-seat--on {
  box-shadow: 0 0 0 6rpx var(--moon);
}

.jd-seat--dead {
  opacity: 0.6;
}

.jd-token {
  width: 100%;
  height: 100%;
}

.jd-token__text {
  font-size: 28rpx;
}

.jd-seat__no {
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

.jd-seat__tag {
  position: absolute;
  right: -6rpx;
  top: -6rpx;
  width: 34rpx;
  height: 34rpx;
  border-radius: 17rpx;
  border: 3rpx solid var(--ink);
  background: var(--moon);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 900;
  line-height: 34rpx;
  text-align: center;
}

.jd-panel {
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.jd-panel__title {
  font-size: 32rpx;
  font-weight: 900;
  color: var(--text);
}

.jd-panel__hint {
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--text-2);
}

.jd-buttons {
  display: flex;
  gap: 16rpx;
}

.jd-button {
  flex: 1;
}
</style>
