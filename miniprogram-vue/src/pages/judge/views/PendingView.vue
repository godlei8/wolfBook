<script setup>
// 出局待办：当事人看到技能询问 / 开枪选人 / 移交警徽（点两侧座位选人）；其他人只看到「等待出局玩家确认」，
// 法官多一行是谁、什么待办。别人的座位上看不出是谁在处理（stageLayout 只给当事人可点的座位）。
// 遗言公开：所有人看到谁在留遗言和倒计时，本人「结束遗言」、房主「跳过」。
import { computed, ref, watch } from 'vue'
import { countdown, phaseTitle } from '../../../utils/judge/room-view.js'
import { pendingPanel } from '../../../utils/judge/pending.js'
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
const pendingKey = computed(() => {
  const item = props.snapshot.pending || props.snapshot.currentPending
  return item ? `${item.kind}-${item.seatNo}` : ''
})
watch(pendingKey, () => {
  selection.value = 0
})

const layout = computed(() => stageLayout(props.snapshot, 'pending', { selection: selection.value, stageHeight: props.stageHeight }))
const panel = computed(() => pendingPanel(props.snapshot, selection.value))
const title = computed(() => phaseTitle(props.snapshot))
const clock = computed(() => countdown(props.snapshot.pending?.deadline || props.snapshot.phaseDeadline, props.now, props.snapshot.paused))
const picked = computed(() => (panel.value.needsTarget && panel.value.targets.includes(selection.value) ? selection.value : 0))
useStageFit(props, emit, () => layout.value.cardHeight)

function seatClass(seat) {
  return { 'nt-seat--dim': seat.dim, 'nt-seat--on': seat.selected, 'nt-seat--speaking': seat.speaking, 'nt-seat--out': seat.out }
}

function tapSeat(seat) {
  if (!seat.tappable) return
  selection.value = selection.value === seat.seatNo ? 0 : seat.seatNo
}

function tapButton(button) {
  if (button.disabled || props.busy) return
  emit('act', { kind: 'skill', decision: button.decision, targetSeatNo: button.target || null })
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
        <image class="nt-scene__bg" src="/static/scenes/moon.webp" mode="aspectFill" webp />
        <view class="nt-scene__body">
          <block v-if="panel.kind === 'waiting'">
            <text class="nt-scene__icon">☾</text>
            <text class="nt-scene__title">{{ panel.title }}</text>
            <text v-if="panel.detail" class="nt-scene__text">{{ panel.detail }}</text>
          </block>
          <block v-else>
            <text class="nt-scene__kicker">{{ panel.kind === 'last_words' ? '遗言' : '出局' }}</text>
            <text class="nt-scene__title">{{ panel.title }}</text>
            <block v-if="panel.needsTarget">
              <text class="nt-scene__big">{{ picked || '?' }}</text>
              <text class="nt-scene__text">{{ picked ? `已选 ${picked} 号` : '点两侧的座位选人' }}</text>
            </block>
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
        <block v-if="panel.kind === 'waiting'">
          <text class="nt-game__lead">{{ panel.title }}</text>
          <text class="nt-game__hint">出局的玩家在自己手机上处理，超时当作不发动。</text>
          <text v-if="panel.detail" class="nt-game__hint">{{ panel.detail }}</text>
        </block>
        <block v-else>
          <text class="nt-game__lead">{{ panel.title }}</text>
          <text v-if="panel.needsTarget" class="nt-game__hint">点两侧的座位选人，再点下面的按钮。</text>
          <text v-if="panel.hint" class="nt-game__hint">{{ panel.hint }}</text>
          <view class="nt-game__buttons">
            <view
              v-for="button in panel.buttons"
              :key="button.decision"
              class="nt-btn nt-game__button"
              :class="[button.decision === 'use' ? 'nt-btn--gold' : 'nt-btn--ghost', { 'nt-btn--disabled': button.disabled || busy }]"
              @tap="tapButton(button)"
            >{{ button.label }}</view>
          </view>
        </block>
      </view>
    </view>
  </view>
</template>
