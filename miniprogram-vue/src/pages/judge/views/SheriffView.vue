<script setup>
// 上警：报名（只看到自己报没报）→ 警上发言（警上的人能退水，发言者本人能结束发言）→ 警长投票 / PK（没上警的人点两侧带「警」的座位投）。
// 房主的推进按钮（截止报名、开始投票等）在房间页底部的操作栏。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { sheriffPanel } from '../../../utils/judge/sheriff.js'
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
watch(() => props.snapshot.currentPhase, () => {
  selection.value = 0
})

const layout = computed(() => stageLayout(props.snapshot, 'sheriff', { selection: selection.value, stageHeight: props.stageHeight }))
const panel = computed(() => sheriffPanel(props.snapshot))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const speaker = computed(() => (props.snapshot.players || []).find((player) => player.seatNo && player.seatNo === panel.value.speakerSeat) || null)
const picked = computed(() => (panel.value.canVote && panel.value.targets.includes(selection.value) ? selection.value : 0))
const info = computed(() => props.snapshot.dayInfo || {})
useStageFit(props, emit, () => layout.value.cardHeight)

function seatClass(seat) {
  return { 'nt-seat--dim': seat.dim, 'nt-seat--on': seat.selected, 'nt-seat--speaking': seat.speaking, 'nt-seat--out': seat.out }
}

function tapSeat(seat) {
  if (!seat.tappable) return
  selection.value = selection.value === seat.seatNo ? 0 : seat.seatNo
}

function send(payload) {
  if (props.busy) return
  emit('act', { kind: 'sheriff', payload })
}

function endSpeech() {
  if (props.busy) return
  emit('act', { kind: 'advance', payload: { action: 'next_speaker' } })
}

function vote() {
  if (!picked.value) return
  send({ targetSeatNo: picked.value })
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
          <block v-if="panel.stage === 'signup'">
            <text class="nt-scene__icon">★</text>
            <text class="nt-scene__title">上警竞选</text>
            <text class="nt-scene__text">警长有 1.5 票，并决定发言顺序</text>
          </block>
          <block v-else-if="panel.stage === 'speech'">
            <text class="nt-scene__kicker">警上发言</text>
            <view v-if="speaker" class="nt-token nt-scene__avatar">
              <image v-if="speaker.avatar" class="nt-token__img" :src="speaker.avatar" mode="aspectFill" />
              <view v-else class="nt-token__text nt-scene__avatar-text">{{ (speaker.nickname || '').slice(0, 1) }}</view>
            </view>
            <text class="nt-scene__title">{{ speaker ? `${speaker.seatNo} 号 ${speaker.nickname}` : '等待发言' }}</text>
            <text class="nt-scene__text">警上：{{ panel.candidates.join('、') || '无' }} 号</text>
          </block>
          <block v-else>
            <text class="nt-scene__kicker">{{ panel.stage === 'pk' ? '警长 PK' : '警长投票' }}</text>
            <text class="nt-scene__big">{{ info.votedCount || 0 }}/{{ info.voterCount || 0 }}</text>
            <text class="nt-scene__text">已投票</text>
            <text v-if="panel.canVote" class="nt-scene__text">{{ picked ? `已选 ${picked} 号` : '点两侧带「警」的座位' }}</text>
          </block>
        </view>
      </view>
    </view>

    <view class="nt-game__head">
      <text class="nt-game__title serif">{{ title }}</text>
      <text class="nt-game__clock" :class="{ 'nt-game__clock--warn': clock.warn }">{{ clock.text }}</text>
    </view>

    <view class="nt-gutter nt-game__area" :style="gameAreaStyle(bar)">
      <view class="nt-panel nt-game__panel">
        <block v-if="panel.stage === 'signup'">
          <text class="nt-game__lead">要上警竞选警长吗？</text>
          <text class="nt-game__hint">{{ panel.choiceText }}</text>
          <view v-if="panel.canSignUp" class="nt-game__buttons">
            <view class="nt-btn nt-game__button" :class="[panel.choice === 'up' ? 'nt-btn--gold' : 'nt-btn--ghost', { 'nt-btn--disabled': busy }]" @tap="send({ candidate: true })">
              {{ panel.choice === 'up' ? '已上警' : '上警' }}
            </view>
            <view class="nt-btn nt-game__button" :class="[panel.choice === 'down' ? 'nt-btn--gold' : 'nt-btn--ghost', { 'nt-btn--disabled': busy }]" @tap="send({ candidate: false })">
              {{ panel.choice === 'down' ? '已选不上警' : '不上警' }}
            </view>
          </view>
        </block>
        <block v-else-if="panel.stage === 'speech'">
          <text class="nt-game__lead">{{ panel.speakerSeat ? `${panel.speakerSeat} 号警上发言` : '警上发言' }}</text>
          <text class="nt-game__hint">警上：{{ panel.candidates.join('、') || '无' }} 号。警上的人可以退水。</text>
          <view v-if="panel.canWithdraw || panel.isSpeaker" class="nt-game__buttons">
            <view v-if="panel.isSpeaker" class="nt-btn nt-btn--gold nt-game__button" :class="{ 'nt-btn--disabled': busy }" @tap="endSpeech">结束发言</view>
            <view v-if="panel.canWithdraw" class="nt-btn nt-btn--ghost nt-game__button" :class="{ 'nt-btn--disabled': busy }" @tap="send({ withdraw: true })">退水</view>
          </view>
        </block>
        <block v-else>
          <text class="nt-game__lead">{{ panel.stage === 'pk' ? '警长 PK 投票' : '警长投票' }}</text>
          <text class="nt-game__hint">{{ panel.progress }}{{ panel.voted ? ` · 你投了 ${panel.myVote} 号` : '' }}</text>
          <text v-if="!panel.canVote" class="nt-game__hint">{{ panel.reason }}</text>
          <view v-if="panel.canVote" class="nt-game__buttons">
            <view class="nt-btn nt-btn--gold nt-game__button" :class="{ 'nt-btn--disabled': !picked || busy }" @tap="vote">
              {{ picked ? `投给 ${picked} 号` : '先点一个警上的座位' }}
            </view>
          </view>
        </block>
      </view>
    </view>
  </view>
</template>
