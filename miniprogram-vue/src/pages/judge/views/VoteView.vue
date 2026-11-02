<script setup>
// 放逐投票 / PK 投票（点两侧的座位选人，进度只显示投了几人）和票型公布（座位上标票数，中间列出加权票数和投票人）。
// 房主的「结算 / 继续」在房间页底部的操作栏。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { voteResultPanel, votePanel } from '../../../utils/judge/day.js'
import { gameAreaStyle, stageLayout } from '../../../utils/judge/stage.js'
import { useStageFit } from '../../../utils/judge/stage-fit.js'

const props = defineProps({
  snapshot: { type: Object, required: true },
  now: { type: Number, required: true },
  busy: { type: Boolean, default: false },
  // 房间页记住的牌桌高度（rpx）和底部有没有固定操作栏
  stageHeight: { type: Number, default: 0 },
  bar: { type: Boolean, default: false },
})
const emit = defineEmits(['act', 'fit'])

const selection = ref(0)
watch(() => `${props.snapshot.currentDay}-${props.snapshot.currentPhase}`, () => {
  selection.value = 0
})

const result = computed(() => props.snapshot.currentPhase === 'vote_result')
const layout = computed(() => stageLayout(props.snapshot, result.value ? 'voteResult' : 'vote', { selection: selection.value, stageHeight: props.stageHeight }))
const panel = computed(() => votePanel(props.snapshot))
const outcome = computed(() => voteResultPanel(props.snapshot))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const info = computed(() => props.snapshot.dayInfo || {})
const picked = computed(() => (panel.value.canVote && panel.value.targets.includes(selection.value) ? selection.value : 0))
const verdict = computed(() => {
  const value = outcome.value
  if (value.revealedIdiot) return `${value.revealedIdiot} 号翻牌是白痴，免于出局`
  if (value.eliminatedSeat) return `${value.eliminatedSeat} 号被放逐`
  if (value.pkSeats.length) return `${value.pkSeats.join('、')} 号平票，进入 PK`
  return '没有人出局'
})
useStageFit(props, emit, () => layout.value.cardHeight)

function seatClass(seat) {
  return { 'nt-seat--dim': seat.dim, 'nt-seat--on': seat.selected, 'nt-seat--speaking': seat.speaking, 'nt-seat--out': seat.out }
}

function tapSeat(seat) {
  if (!seat.tappable) return
  selection.value = selection.value === seat.seatNo ? 0 : seat.seatNo
}

function vote(targetSeatNo) {
  if (props.busy) return
  emit('act', { kind: 'vote', targetSeatNo })
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
          <text v-if="seat.tag" class="nt-seat__tag" :class="seat.tagKind ? `nt-seat__tag--${seat.tagKind}` : ''">{{ seat.tag }}</text>
          <text v-if="seat.sheriff" class="nt-seat__sheriff">★</text>
        </view>
      </view>

      <view class="nt-stage__center nt-card" :style="{ height: `${layout.cardHeight}rpx` }">
        <image class="nt-scene__bg" src="/static/scenes/square.webp" mode="aspectFill" webp />
        <view class="nt-scene__body">
          <block v-if="!result">
            <text class="nt-scene__kicker">{{ panel.stage === 'pk' ? 'PK 投票' : '放逐投票' }}</text>
            <text class="nt-scene__big">{{ info.votedCount || 0 }}/{{ info.voterCount || 0 }}</text>
            <text class="nt-scene__text">已投票</text>
            <text v-if="panel.canVote" class="nt-scene__text">{{ picked ? `已选 ${picked} 号` : '点两侧的座位选人' }}</text>
            <text v-else-if="panel.mine" class="nt-scene__text">{{ panel.mine }}</text>
          </block>
          <block v-else>
            <text class="nt-scene__kicker">票型</text>
            <text class="nt-scene__title">{{ verdict }}</text>
            <view class="jv-tallies">
              <view v-for="tally in outcome.tallies" :key="tally.targetSeatNo" class="jv-tally">
                <view class="jv-tally__head">
                  <text class="jv-tally__target">{{ tally.targetSeatNo }} 号</text>
                  <text class="jv-tally__weight">{{ tally.weight }} 票</text>
                </view>
                <text class="jv-tally__voters">← {{ tally.voters }}</text>
              </view>
            </view>
          </block>
        </view>
      </view>
    </view>

    <view class="nt-game__head">
      <text class="nt-game__title serif">{{ title }}</text>
      <text v-if="!result" class="nt-game__clock" :class="{ 'nt-game__clock--warn': clock.warn }">{{ clock.text }}</text>
    </view>

    <view class="nt-gutter nt-game__area" :style="gameAreaStyle(bar)">
      <view class="nt-panel nt-game__panel">
        <block v-if="!result">
          <text class="nt-game__lead">{{ panel.stage === 'pk' ? 'PK 投票：只能投平票的人' : '投出你怀疑的人' }}</text>
          <text class="nt-game__hint">{{ panel.progress }}{{ panel.mine ? ` · ${panel.mine}` : '' }}</text>
          <text v-if="!panel.canVote" class="nt-game__hint">{{ panel.reason }}</text>
          <view v-if="panel.canVote" class="nt-game__buttons">
            <view class="nt-btn nt-btn--gold nt-game__button" :class="{ 'nt-btn--disabled': !picked || busy }" @tap="picked && vote(picked)">
              {{ picked ? `投给 ${picked} 号` : '先点一个座位' }}
            </view>
            <view class="nt-btn nt-btn--ghost nt-game__button" :class="{ 'nt-btn--disabled': busy }" @tap="vote(null)">弃票</view>
          </view>
        </block>
        <block v-else>
          <text class="nt-game__lead">{{ verdict }}</text>
          <text class="nt-game__hint">弃票：{{ outcome.abstain || '无' }}</text>
          <text class="nt-game__hint">座位左上角是得票数（警长 1.5 票）。房主点「继续」往下走。</text>
        </block>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jv-tallies {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  gap: 14rpx;
  max-height: 60%;
  overflow-y: auto;
}

.jv-tally {
  padding: 12rpx 16rpx;
  border-radius: 16rpx;
  background: rgba(7, 6, 10, 0.55);
  text-align: left;
}

.jv-tally__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.jv-tally__target {
  font-family: var(--serif);
  font-size: 30rpx;
  font-weight: 900;
  color: var(--gold);
}

.jv-tally__weight {
  font-size: 24rpx;
  font-weight: 700;
  color: var(--text);
}

.jv-tally__voters {
  font-size: 22rpx;
  color: var(--text-2);
}
</style>
