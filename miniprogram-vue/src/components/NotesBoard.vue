<script setup>
// 对局笔记的主体（合并版）：座位盘 + 票型 + 点座位的面板 + 两步记票。笔记页和法官房间共用，页面直接用这一层。
// 画面上的判断都在 utils/notes/ 的纯函数里；这里只管当前看哪一天、选中哪个座位，每次点击算出新笔记后 emit('change')。
// 里面不嵌别的自定义组件（小程序里组件套组件会不渲染）。
import { computed, getCurrentInstance, nextTick, ref, watch } from 'vue'
import { addDay, dayOf } from '../utils/notes/model.js'
import {
  addTargetTag, daySummary, markPanel, seatCards, setGuess, setJudgment, setSayText,
  toggleClaim, toggleOut, togglePeace, toggleSheriff, toggleTag, wolfPit,
} from '../utils/notes/board.js'
import { entryView, finishEntry, pickTarget, restAbstain, startEntry, toggleVoter, voteView } from '../utils/notes/votes.js'
import { applyDockChip, speechDock } from '../utils/notes/speech.js'

const props = defineProps({
  note: { type: Object, required: true },
  // 画面状态：我是几号、谁在发言、各座位的昵称和头像（法官房间里才有）
  me: { type: Number, default: 0 },
  speaking: { type: Number, default: 0 },
  names: { type: Object, default: () => ({}) },
  avatars: { type: Object, default: () => ({}) },
  // 法官房间里：出局、警长、票型是自动记的，不能手改；天数跟着房间走（today 是房间现在第几天）
  room: { type: Boolean, default: false },
  today: { type: Number, default: 0 },
})
// reveal：点开的座位被面板挡住了，请外面把内容往上滚这么多像素；settle：面板收起了，可以滚回原来的位置
const emit = defineEmits(['change', 'reveal', 'settle'])
const instance = getCurrentInstance()

const tab = ref('seats')
const day = ref(props.today || props.note.days || 1)
const selected = ref(0)
// 点了「站边 / 踩人 / 报金水 / 报查杀」，等再点一个座位
const pending = ref('')
// 正在记票（两步）
const entry = ref(null)
// 正在写字：{ kind: 'say' | 'judgment', value }
const editing = ref(null)

watch(() => props.note.days, (days) => {
  if (day.value > days) day.value = days
})
// 房间进到新的一天，笔记跟着翻到那一天
watch(() => props.today, (today) => {
  if (today) day.value = Math.min(today, props.note.days || today)
})

const days = computed(() => Array.from({ length: props.note.days || 1 }, (_, index) => index + 1))
// 那一排滚到「当前这一天的前一格」：当前这一天和它前后各一格都看得见
const activeTab = computed(() => `nb-tab-${Math.max(1, day.value - 1)}`)
const cards = computed(() => seatCards(props.note, day.value, {
  selected: selected.value, speaking: props.speaking, me: props.me, names: props.names, avatars: props.avatars,
}))
const pit = computed(() => wolfPit(props.note))
const judgment = computed(() => dayOf(props.note, day.value).judgment)
const summary = computed(() => daySummary(props.note, day.value, { room: props.room }))
const panel = computed(() => (selected.value ? markPanel(props.note, selected.value, day.value, pending.value, { room: props.room }) : null))
// 发言快捷条：只在房间里、有人发言、没开座位面板时有；记到房间当天
const dockDay = computed(() => props.today || day.value)
const dock = computed(() => (props.room && tab.value === 'seats' && !selected.value ? speechDock(props.note, dockDay.value, props.speaking) : null))
// 快捷条上「+ 写一句」单独放在标题那一行的右边，其余的排在下面
const dockWrite = computed(() => dock.value?.chips.find((chip) => chip.kind === 'text') || null)
const dockChips = computed(() => (dock.value ? dock.value.chips.filter((chip) => chip.kind !== 'text') : []))
const voteDays = computed(() => days.value.map((item) => voteView(props.note, item, { avatars: props.avatars })))
const entryScreen = computed(() => (entry.value ? entryView(props.note, entry.value) : null))

function update(next) {
  emit('change', next)
}

// 点开一个座位后量一下：它要是被面板挡住了，告诉外面往上滚多少，让这一排停在面板上方
function revealSelected() {
  nextTick(() => {
    setTimeout(() => {
      if (!selected.value) return
      const query = uni.createSelectorQuery().in(instance.proxy)
      query.select(`#nb-seat-${selected.value}`).boundingClientRect()
      query.select('.nb-panel').boundingClientRect()
      query.exec((rects) => {
        const [seat, sheet] = rects || []
        if (!seat || !sheet) return
        const delta = seat.bottom + 10 - sheet.top
        if (delta > 0) emit('reveal', Math.ceil(delta))
      })
    }, 80)
  })
}
watch(selected, (no) => {
  if (no) revealSelected()
  else emit('settle')
})

function pickDay(value) {
  day.value = value
  pending.value = ''
  editing.value = null
}

function nextDay() {
  const next = addDay(props.note)
  update(next)
  day.value = next.days
  selected.value = 0
  pending.value = ''
}

function switchTab(value) {
  tab.value = value
  selected.value = 0
  pending.value = ''
  editing.value = null
}

function tapSeat(no) {
  if (pending.value && selected.value) {
    update(addTargetTag(props.note, day.value, selected.value, pending.value, no))
    pending.value = ''
    return
  }
  editing.value = null
  selected.value = selected.value === no ? 0 : no
}

function closePanel() {
  selected.value = 0
  pending.value = ''
  editing.value = null
}

function tapAction(action) {
  const no = selected.value
  if (action.kind === 'idle') {
    update(toggleTag(props.note, day.value, no, '划水'))
  } else if (action.kind === 'text') {
    editing.value = { kind: 'say', value: panel.value.text }
  } else {
    pending.value = pending.value === action.kind ? '' : action.kind
  }
}

function tapStatus(item) {
  const no = selected.value
  update(item.kind === 'sheriff' ? toggleSheriff(props.note, no) : toggleOut(props.note, no, item.kind, day.value))
}

function onEditInput(event) {
  editing.value = { ...editing.value, value: event.detail?.value || '' }
}

function saveEdit() {
  if (!editing.value) return
  if (editing.value.kind === 'say') update(setSayText(props.note, day.value, selected.value, editing.value.value))
  else update(setJudgment(props.note, day.value, editing.value.value))
  editing.value = null
}

function editJudgment() {
  selected.value = 0
  pending.value = ''
  editing.value = { kind: 'judgment', value: judgment.value }
}

function tapDock(chip) {
  const next = applyDockChip(props.note, dockDay.value, props.speaking, chip)
  if (next) {
    update(next)
    return
  }
  // 要再点座位或打字的：打开发言人的面板接着做
  day.value = dockDay.value
  selected.value = props.speaking
  pending.value = chip.kind === 'stand' || chip.kind === 'step' ? chip.kind : ''
  editing.value = chip.kind === 'text' ? { kind: 'say', value: markPanel(props.note, props.speaking, dockDay.value).text } : null
}

// kind：'sheriff' 记警长竞选的票，不传是放逐票
function openEntry(value, kind) {
  entry.value = startEntry(props.note, value, kind)
}

function finish() {
  update(finishEntry(props.note, entry.value))
  day.value = entry.value.day
  entry.value = null
}
</script>

<template>
  <view class="nb">
    <!-- 记票（两步）：盖住下面的内容 -->
    <view v-if="entryScreen" class="nb-entry">
      <view class="nb-entry__head">
        <text class="nb-h">{{ entryScreen.title }}</text>
        <text class="nb-link" @tap="entry = null">取消</text>
      </view>

      <view class="nb-step">
        <text class="nb-step__no serif">1</text>
        <text class="nb-step__text">先点被投的人</text>
      </view>
      <view class="nb-picks">
        <view
          v-for="item in entryScreen.targets"
          :key="item.no"
          class="nb-pick"
          :class="{ 'nb-pick--gold': item.on, 'nb-pick--done': item.done && !item.on }"
          @tap="entry = pickTarget(entry, item.no)"
        >
          <text class="nb-pick__no serif">{{ item.no }}</text>
        </view>
      </view>

      <view class="nb-step">
        <text class="nb-step__no serif">2</text>
        <text class="nb-step__text">{{ entryScreen.step2 }}</text>
        <text class="nb-step__count">{{ entryScreen.pickedText }}</text>
      </view>
      <view class="nb-picks">
        <view
          v-for="item in entryScreen.voters"
          :key="item.no"
          class="nb-pick nb-pick--tall"
          :class="{ 'nb-pick--on': item.on, 'nb-pick--used': item.used }"
          @tap="entry = toggleVoter(entry, item.no)"
        >
          <text class="nb-pick__no serif">{{ item.no }}</text>
          <text class="nb-pick__label">{{ item.label }}</text>
        </view>
      </view>

      <view class="nb-card">
        <text class="nb-card__title serif">已经记下</text>
        <text v-if="!entryScreen.lines.length" class="nb-muted">还没有，先点被投的人，再点投他的人。</text>
        <view v-for="line in entryScreen.lines" :key="line.target" class="nb-line">
          <text class="nb-line__text"><text class="nb-line__title">{{ line.title }}</text>　{{ line.votersText }}</text>
          <text class="nb-muted">{{ line.countText }}</text>
        </view>
        <view v-if="entryScreen.remainingText" class="nb-line nb-line--rest">
          <text class="nb-muted">{{ entryScreen.remainingText }}</text>
          <text v-if="entryScreen.canAbstain" class="nb-chip" @tap="entry = restAbstain(note, entry)">其余算弃票</text>
        </view>
        <text class="nb-muted">{{ entryScreen.hint }}</text>
      </view>

      <view class="nb-entry__foot">
        <view class="nt-btn nt-btn--gold nt-btn--block" @tap="finish">记完，写进票型</view>
      </view>
    </view>

    <block v-else>
      <view class="nb-top">
        <scroll-view class="nb-days" scroll-x enable-flex :show-scrollbar="false" scroll-with-animation :scroll-into-view="activeTab">
          <view class="nb-days__inner">
            <text
              v-for="item in days"
              :id="`nb-tab-${item}`"
              :key="item"
              class="nb-chip"
              :class="{ 'nb-chip--on': item === day }"
              @tap="pickDay(item)"
            >第 {{ item }} 天</text>
            <text v-if="!room" class="nb-chip nb-chip--dashed" @tap="nextDay">+ 下一天</text>
          </view>
        </scroll-view>
        <view class="nb-seg">
          <text class="nb-seg__item" :class="{ 'nb-seg__item--on': tab === 'seats' }" @tap="switchTab('seats')">座位</text>
          <text class="nb-seg__item" :class="{ 'nb-seg__item--on': tab === 'votes' }" @tap="switchTab('votes')">票型</text>
        </view>
      </view>

      <!-- 座位盘 -->
      <block v-if="tab === 'seats'">
        <!-- 这一天顶上一行：昨夜谁出局、警长是谁。面杀时旁边可以点「平安夜」 -->
        <view v-if="summary.text" class="nb-sum">
          <text class="nb-sum__text" :class="{ 'nb-sum__text--todo': summary.nightMissing }">{{ summary.text }}</text>
          <text v-if="summary.canPeace" class="nb-chip nb-sum__peace" :class="{ 'nb-chip--on': summary.peace }" @tap="update(togglePeace(note, day))">平安夜</text>
        </view>

        <view class="nb-grid">
          <view
            v-for="card in cards"
            :id="`nb-seat-${card.no}`"
            :key="card.no"
            class="nb-seat"
            :class="[`nb-seat--${card.tone}`, {
              'nb-seat--named': room,
              'nb-seat--blank': card.blank,
              'nb-seat--gone': card.gone,
              'nb-seat--ring': card.selected || card.speaking,
            }]"
            @tap="tapSeat(card.no)"
          >
            <!-- 角上的徽标：警长、我 -->
            <view v-if="card.sheriff || card.me" class="nb-seat__flags">
              <text v-if="card.sheriff" class="nb-flag nb-flag--sheriff">警</text>
              <text v-if="card.me" class="nb-flag nb-flag--me">我</text>
            </view>
            <view class="nb-seat__head">
              <view class="nb-seat__who">
                <text class="nb-seat__no serif">{{ card.no }}</text>
                <image v-if="card.avatar" class="nb-seat__avatar" :src="card.avatar" mode="aspectFill" />
              </view>
              <text v-if="card.guessShort" class="nb-seat__guess" :class="`nb-pill--${card.tone}`">{{ card.guessShort }}</text>
            </view>
            <text v-if="room" class="nb-seat__name">{{ card.name }}</text>
            <block v-if="!card.blank">
              <text class="nb-seat__claim" :class="{ 'nb-seat__claim--standoff': card.standoff }">{{ card.claim }}</text>
              <text v-if="card.gone" class="nb-seat__out">{{ card.out }}</text>
              <view v-else class="nb-seat__marks">
                <text v-for="mark in card.marks" :key="mark.text" class="nb-mark" :class="`nb-mark--${mark.tone}`">{{ mark.text }}</text>
              </view>
            </block>
          </view>
        </view>

        <view class="nb-pit">
          <text class="nb-muted">我的狼坑</text>
          <text v-for="seat in pit.seats" :key="seat.no" class="nb-pit__seat" :class="{ 'nb-pit__seat--gone': seat.gone }">{{ seat.no }}{{ seat.gone ? ' 已出局' : '' }}</text>
          <text class="nb-muted">{{ pit.text }}</text>
        </view>

        <view v-if="editing && editing.kind === 'judgment'" class="nb-judgment">
          <text class="nb-judgment__label">第 {{ day }} 天我的判断</text>
          <textarea
            class="nb-input nb-input--area"
            :value="editing.value"
            maxlength="200"
            placeholder="比如：4、5、9 三张票冲警长，像一个狼坑"
            placeholder-class="nt-placeholder"
            auto-focus
            @input="onEditInput"
          />
          <view class="nb-row">
            <text class="nb-chip" @tap="editing = null">取消</text>
            <text class="nb-chip nb-chip--on" @tap="saveEdit">记下</text>
          </view>
        </view>
        <view v-else class="nb-judgment" @tap="editJudgment">
          <text class="nb-judgment__label">第 {{ day }} 天我的判断 · 点这里{{ judgment ? '改' : '写' }}</text>
          <text v-if="judgment" class="nb-judgment__text">{{ judgment }}</text>
          <text v-else class="nb-muted">这一天打完，你觉得谁是狼、为什么。</text>
        </view>

        <view v-if="dock" class="nb-dock">
          <view class="nb-dock__head">
            <text class="nb-dock__title serif">{{ dock.title }}</text>
            <text v-if="dockWrite" class="nb-chip nb-chip--dashed" @tap="tapDock(dockWrite)">{{ dockWrite.label }}</text>
          </view>
          <view class="nb-chips">
            <text
              v-for="chip in dockChips"
              :key="chip.label"
              class="nb-chip"
              :class="{ 'nb-chip--on': chip.on }"
              @tap="tapDock(chip)"
            >{{ chip.label }}</text>
          </view>
        </view>

        <!-- 面板打开时垫高，被挡住的座位能滚上来 -->
        <view v-if="panel" class="nb-spacer" />
      </block>

      <!-- 票型 -->
      <block v-else>
        <view class="nb-legend">
          <text class="nb-muted">边框颜色 = 你在座位上标的</text>
          <view class="nb-legend__item"><view class="nb-dot nb-dot--wolf" /><text class="nb-muted">狼</text></view>
          <view class="nb-legend__item"><view class="nb-dot nb-dot--good" /><text class="nb-muted">好</text></view>
          <view class="nb-legend__item"><view class="nb-dot nb-dot--god" /><text class="nb-muted">神</text></view>
        </view>

        <view v-for="item in voteDays" :key="item.day" class="nb-card" :class="{ 'nb-card--dashed': !item.recorded && !item.election }">
          <view class="nb-card__head">
            <text class="nb-card__title serif">{{ item.title }}</text>
            <text v-if="!room" class="nb-link" @tap="openEntry(item.day)">{{ item.recorded ? '改' : '记票' }}</text>
          </view>
          <!-- 警长竞选：房间里自动记，面杀点「记警长票」 -->
          <view v-if="item.election" class="nb-election">
            <view class="nb-card__head">
              <text class="nb-election__title">{{ item.election.title }}</text>
              <text v-if="!room" class="nb-link" @tap="openEntry(item.day, 'sheriff')">改</text>
            </view>
            <view v-if="item.election.up.length" class="nb-bar__row">
              <text class="nb-muted">上警</text>
              <view class="nb-bar__voters nb-bar__voters--flat">
                <view
                  v-for="person in item.election.up"
                  :key="person.no"
                  class="nb-av nb-av--s"
                  :class="[`nb-av--${person.tone}`, { 'nb-av--off': person.off }]"
                >
                  <image v-if="person.avatar" class="nb-av__img" :src="person.avatar" mode="aspectFill" />
                  <text v-if="person.avatar" class="nb-av__no">{{ person.no }}</text>
                  <text v-else class="nb-av__num serif">{{ person.no }}</text>
                  <text v-if="person.elected" class="nb-av__badge">警</text>
                </view>
              </view>
            </view>
            <view v-for="group in item.election.groups" :key="group.target" class="nb-bar">
              <view class="nb-bar__row">
                <view class="nb-av nb-av--m" :class="`nb-av--${group.tone}`">
                  <image v-if="group.avatar" class="nb-av__img" :src="group.avatar" mode="aspectFill" />
                  <text v-if="group.avatar" class="nb-av__no">{{ group.target }}</text>
                  <text v-else class="nb-av__num serif">{{ group.target }}</text>
                </view>
                <view class="nb-bar__track">
                  <view class="nb-bar__fill" :class="{ 'nb-bar__fill--top': group.top }" :style="{ width: `${group.pct}%` }" />
                </view>
                <text class="nb-bar__count">{{ group.countText }}</text>
              </view>
              <view class="nb-bar__voters">
                <view v-for="voter in group.voters" :key="voter.no" class="nb-av nb-av--s" :class="`nb-av--${voter.tone}`">
                  <image v-if="voter.avatar" class="nb-av__img" :src="voter.avatar" mode="aspectFill" />
                  <text v-if="voter.avatar" class="nb-av__no">{{ voter.no }}</text>
                  <text v-else class="nb-av__num serif">{{ voter.no }}</text>
                </view>
              </view>
              <text v-if="group.hint" class="nb-group__hint nb-bar__hint">{{ group.hint }}</text>
            </view>
          </view>
          <text v-else-if="!room && item.day <= 2" class="nb-chip nb-chip--dashed nb-election__add" @tap="openEntry(item.day, 'sheriff')">+ 记警长竞选的票</text>

          <text v-if="item.election && (item.recorded || room)" class="nb-election__title">放逐投票</text>
          <text v-if="!item.recorded" class="nb-muted">{{ room ? '投票结束后自动填入。' : '点「记票」：先点被投的人，再点投他的人。' }}</text>
          <view v-for="group in item.groups" :key="group.target" class="nb-bar">
            <view class="nb-bar__row">
              <view class="nb-av nb-av--m" :class="`nb-av--${group.tone}`">
                <image v-if="group.avatar" class="nb-av__img" :src="group.avatar" mode="aspectFill" />
                <text v-if="group.avatar" class="nb-av__no">{{ group.target }}</text>
                <text v-else class="nb-av__num serif">{{ group.target }}</text>
              </view>
              <view class="nb-bar__track">
                <view class="nb-bar__fill" :class="{ 'nb-bar__fill--top': group.top }" :style="{ width: `${group.pct}%` }" />
              </view>
              <text class="nb-bar__count">{{ group.countText }}</text>
            </view>
            <view class="nb-bar__voters">
              <view v-for="voter in group.voters" :key="voter.no" class="nb-av nb-av--s" :class="`nb-av--${voter.tone}`">
                <image v-if="voter.avatar" class="nb-av__img" :src="voter.avatar" mode="aspectFill" />
                <text v-if="voter.avatar" class="nb-av__no">{{ voter.no }}</text>
                <text v-else class="nb-av__num serif">{{ voter.no }}</text>
              </view>
            </view>
            <text v-if="group.hint" class="nb-group__hint nb-bar__hint">{{ group.hint }}</text>
          </view>
          <view v-if="item.abstain.length" class="nb-bar__row nb-bar__row--abstain">
            <text class="nb-muted">弃票</text>
            <view class="nb-bar__voters nb-bar__voters--flat">
              <view v-for="voter in item.abstain" :key="voter.no" class="nb-av nb-av--s" :class="`nb-av--${voter.tone}`">
                <image v-if="voter.avatar" class="nb-av__img" :src="voter.avatar" mode="aspectFill" />
                <text v-if="voter.avatar" class="nb-av__no">{{ voter.no }}</text>
                <text v-else class="nb-av__num serif">{{ voter.no }}</text>
              </view>
            </view>
          </view>
          <view v-if="item.judgment" class="nb-quote">
            <text class="nb-judgment__label">我的判断</text>
            <text class="nb-judgment__text">{{ item.judgment }}</text>
          </view>
        </view>
      </block>
    </block>

    <!-- 点座位的面板：点了马上生效 -->
    <view v-if="panel && !entryScreen" class="nb-panel">
      <!-- 点了站边 / 踩人 / 报金水 / 报查杀：直接在面板里选号码，不用滚回上面点座位 -->
      <block v-if="panel.picker">
        <view class="nb-panel__head">
          <text class="nb-panel__no serif">{{ panel.picker.title }}</text>
          <text class="nb-panel__hint">点一个号码</text>
        </view>
        <view class="nb-targets">
          <view
            v-for="seat in panel.picker.seats"
            :key="seat.no"
            class="nb-av nb-av--pick"
            :class="[`nb-av--${seat.tone}`, { 'nb-av--off': seat.gone, 'nb-av--picked': seat.on }]"
            @tap="tapSeat(seat.no)"
          >
            <image v-if="avatars[seat.no]" class="nb-av__img" :src="avatars[seat.no]" mode="aspectFill" />
            <text v-if="avatars[seat.no]" class="nb-av__no">{{ seat.no }}</text>
            <text v-else class="nb-av__num serif">{{ seat.no }}</text>
          </view>
        </view>
        <text class="nb-status__item nb-status__item--back" @tap="pending = ''">不选了</text>
      </block>
      <block v-else>
      <view class="nb-panel__head">
        <view class="nb-panel__title">
          <text class="nb-panel__no serif">{{ panel.title }}</text>
          <text class="nb-muted">{{ names[panel.no] || '' }}</text>
        </view>
        <text class="nb-panel__hint">点了马上生效，不用保存</text>
      </view>

      <text class="nb-label">我猜他是</text>
      <view class="nb-guesses">
        <text
          v-for="item in panel.guesses"
          :key="item.label"
          class="nb-guess"
          :class="[item.value ? `nb-guess--${item.value}` : 'nb-guess--clear', { 'nb-guess--on': item.on }]"
          @tap="update(setGuess(note, panel.no, item.value))"
        >{{ item.label }}</text>
      </view>

      <text class="nb-label">他说自己是</text>
      <view class="nb-chips">
        <text
          v-for="item in panel.claims"
          :key="item.value"
          class="nb-chip"
          :class="{ 'nb-chip--on': item.on }"
          @tap="update(toggleClaim(note, panel.no, item.value))"
        >{{ item.value }}</text>
      </view>

      <text class="nb-label">{{ panel.dayLabel }}</text>
      <view class="nb-chips">
        <text
          v-for="tag in panel.tags"
          :key="tag"
          class="nb-chip nb-chip--on"
          @tap="update(toggleTag(note, day, panel.no, tag))"
        >{{ tag }} ×</text>
        <text
          v-for="item in panel.actions"
          :key="item.kind"
          class="nb-chip"
          :class="{ 'nb-chip--pending': item.on && item.kind !== 'idle', 'nb-chip--on': item.on && item.kind === 'idle', 'nb-chip--dashed': item.kind === 'text' }"
          @tap="tapAction(item)"
        >{{ item.kind === 'text' && panel.text ? '改那句话' : item.label }}</text>
      </view>
      <view v-if="editing && editing.kind === 'say'" class="nb-row">
        <input
          class="nb-input"
          :value="editing.value"
          maxlength="60"
          placeholder="一句话记下他说了什么"
          placeholder-class="nt-placeholder"
          focus
          confirm-type="done"
          @input="onEditInput"
          @confirm="saveEdit"
        />
        <text class="nb-chip nb-chip--on" @tap="saveEdit">记下</text>
      </view>
      <text v-else-if="panel.text" class="nb-panel__text">{{ panel.text }}</text>

      <view v-if="panel.status.length" class="nb-status">
        <text
          v-for="item in panel.status"
          :key="item.kind"
          class="nb-status__item"
          :class="{ 'nb-status__item--on': item.on }"
          @tap="tapStatus(item)"
        >{{ item.label }}</text>
      </view>
      <text class="nb-status__item nb-status__item--close" @tap="closePanel">完成</text>
      </block>
    </view>
  </view>
</template>

<style scoped lang="scss">
.nb {
  --nb-wolf-bg: #43212a;
  --nb-good-bg: #1d3529;
  --nb-god-bg: #3d331a;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.nb-top {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.nb-days {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
}

.nb-days__inner {
  display: inline-flex;
  gap: 12rpx;
  padding-right: 8rpx;
}

/* 顶上那排页签比面板里的按钮小一号 */
.nb-days__inner .nb-chip {
  height: 72rpx;
  padding: 0 24rpx;
  border-radius: 36rpx;
  font-size: 26rpx;
}

.nb-chip {
  flex-shrink: 0;
  height: 88rpx;
  padding: 0 28rpx;
  box-sizing: border-box;
  border-radius: 44rpx;
  border: 3rpx solid var(--line);
  background: #1c1824;
  color: #c9bfd8;
  font-size: 28rpx;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
}

.nb-chip--on {
  border: 4rpx solid var(--ink);
  background: var(--text);
  color: var(--ink);
  font-weight: 700;
}

.nb-chip--pending {
  border-color: var(--moon);
  color: var(--moon);
}

.nb-chip--dashed {
  border-style: dashed;
  border-color: var(--line-strong);
  background: transparent;
  color: var(--text-soft);
}

.nb-seg {
  flex-shrink: 0;
  display: flex;
  border-radius: 32rpx;
  background: var(--panel-sunk);
  border: 3rpx solid var(--line);
  overflow: hidden;
}

.nb-seg__item {
  height: 72rpx;
  padding: 0 24rpx;
  border-radius: 28rpx;
  display: flex;
  align-items: center;
  font-size: 26rpx;
  color: #c9bfd8;
}

.nb-seg__item--on {
  background: var(--text);
  color: var(--ink);
  font-weight: 700;
}

.nb-sum {
  min-height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.nb-sum .nb-sum__peace {
  height: 64rpx;
  padding: 0 22rpx;
  border-radius: 32rpx;
  font-size: 26rpx;
}

.nb-sum__text {
  font-size: 26rpx;
  line-height: 1.4;
  color: var(--text-soft);
}

.nb-sum__text--todo {
  color: var(--moon);
}

/* 号码块：号码都在左上角。没标过的底色安静，标了才上色、长出内容，一眼看得出哪些座位记过 */
.nb-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20rpx 14rpx;
  padding-top: 8rpx;
}

.nb-seat {
  position: relative;
  height: 156rpx;
  padding: 12rpx 14rpx;
  box-sizing: border-box;
  border-radius: 28rpx;
  border: 4rpx solid var(--ink);
  background: var(--panel-raised);
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

/* 法官房间里多一行头像和昵称 */
.nb-seat--named {
  height: 194rpx;
}

.nb-seat--blank {
  border: 3rpx solid var(--line);
  background: var(--panel-sunk);
}

.nb-seat--wolf {
  background: var(--nb-wolf-bg);
  border-color: var(--camp-wolf);
}

.nb-seat--good {
  background: var(--nb-good-bg);
  border-color: var(--camp-villager);
}

.nb-seat--god {
  background: var(--nb-god-bg);
  border-color: var(--camp-god);
}

.nb-seat--gone {
  opacity: 0.5;
}

.nb-seat--ring {
  box-shadow: 0 0 0 6rpx var(--moon);
}

.nb-seat__flags {
  position: absolute;
  top: -20rpx;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 6rpx;
}

.nb-flag {
  width: 40rpx;
  height: 40rpx;
  box-sizing: border-box;
  border-radius: 50%;
  border: 3rpx solid var(--ink);
  font-size: 22rpx;
  font-weight: 900;
  line-height: 1;
  color: var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
}

.nb-flag--sheriff {
  background: var(--gold);
}

.nb-flag--me {
  background: var(--moon);
}

.nb-seat__head {
  height: 46rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4rpx;
}

.nb-seat__who {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6rpx;
}

.nb-seat__no {
  font-size: 38rpx;
  font-weight: 900;
  line-height: 1;
}

.nb-seat--blank .nb-seat__no {
  color: var(--text-soft);
}

.nb-seat__guess {
  flex-shrink: 0;
  width: 36rpx;
  height: 36rpx;
  box-sizing: border-box;
  border-radius: 10rpx;
  font-size: 22rpx;
  font-weight: 900;
  line-height: 1;
  color: var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
}

.nb-pill--wolf {
  background: var(--camp-wolf);
}

.nb-pill--good {
  background: var(--camp-villager);
}

.nb-pill--god {
  background: var(--camp-god);
}

.nb-seat__avatar {
  flex-shrink: 0;
  width: 30rpx;
  height: 30rpx;
  border-radius: 50%;
  background: var(--panel-line);
}

.nb-seat__name,
.nb-seat__claim,
.nb-seat__out {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nb-seat__name {
  width: 100%;
  height: 32rpx;
  font-size: 22rpx;
  line-height: 32rpx;
  color: var(--text-2);
}

.nb-seat__claim {
  height: 30rpx;
  font-size: 22rpx;
  line-height: 30rpx;
  font-weight: 700;
  color: var(--gold);
}

.nb-seat__claim--standoff {
  color: var(--camp-wolf);
}

.nb-seat__out {
  height: 36rpx;
  font-size: 20rpx;
  letter-spacing: -1rpx;
  line-height: 36rpx;
  color: var(--text-soft);
}

.nb-seat__marks {
  height: 36rpx;
  display: flex;
  gap: 6rpx;
  overflow: hidden;
}

.nb-mark {
  flex-shrink: 0;
  height: 36rpx;
  padding: 0 8rpx;
  box-sizing: border-box;
  border-radius: 10rpx;
  border: 2rpx solid var(--line-strong);
  font-size: 22rpx;
  line-height: 32rpx;
  font-weight: 700;
  color: var(--text-soft);
  white-space: nowrap;
}

.nb-mark--stand {
  border-color: var(--camp-villager);
  color: var(--camp-villager);
}

.nb-mark--step {
  border-color: var(--camp-wolf);
  color: var(--camp-wolf);
}

.nb-mark--gold {
  border-color: var(--gold);
  background: var(--gold);
  color: var(--ink);
}

.nb-mark--kill {
  border-color: var(--camp-wolf);
  background: var(--camp-wolf);
  color: var(--ink);
}

.nb-mark--more {
  border-style: dashed;
  font-weight: 400;
}

.nb-muted {
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--text-2);
}

.nb-pit {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12rpx 16rpx;
}

.nb-pit__seat {
  height: 52rpx;
  padding: 0 20rpx;
  border-radius: 26rpx;
  background: var(--camp-wolf);
  color: var(--ink);
  font-size: 26rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
}

.nb-pit__seat--gone {
  background: transparent;
  border: 3rpx solid var(--camp-wolf);
  color: var(--camp-wolf);
  font-weight: 400;
  box-sizing: border-box;
}

.nb-judgment,
.nb-quote {
  background: var(--panel-sunk);
  border: 3rpx solid var(--gold);
  border-radius: 28rpx;
  padding: 16rpx 24rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.nb-quote {
  border-width: 2rpx;
}

.nb-judgment__label {
  font-size: 24rpx;
  color: var(--gold);
}

.nb-judgment__text {
  font-size: 28rpx;
  line-height: 1.5;
  color: var(--text);
}

.nb-judgment .nb-row {
  padding-top: 4rpx;
  gap: 12rpx;
}

.nb-judgment .nb-chip {
  height: 60rpx;
  padding: 0 26rpx;
  border-radius: 30rpx;
  font-size: 26rpx;
}

/* 座位面板里「写一句」那一行 */
.nb-panel .nb-input {
  height: 64rpx;
  border-radius: 24rpx;
  font-size: 26rpx;
}

.nb-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16rpx;
}

.nb-input {
  flex: 1;
  min-width: 0;
  height: 88rpx;
  padding: 0 24rpx;
  box-sizing: border-box;
  border-radius: 28rpx;
  border: 3rpx solid var(--line-strong);
  background: var(--panel-sunk);
  color: var(--text);
  font-size: 28rpx;
}

.nb-input--area {
  flex: none;
  width: 100%;
  height: 144rpx;
  padding: 16rpx 20rpx;
  border-radius: 20rpx;
  line-height: 1.5;
  font-size: 26rpx;
}

.nb-dock {
  background: var(--panel-sunk);
  border: 3rpx solid var(--moon);
  border-radius: 28rpx;
  padding: 16rpx 20rpx 20rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.nb-dock__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.nb-dock .nb-chips {
  gap: 12rpx;
}

.nb-dock .nb-chip {
  height: 60rpx;
  padding: 0 22rpx;
  border-radius: 30rpx;
  font-size: 26rpx;
}

.nb-dock__title {
  font-size: 28rpx;
  font-weight: 900;
}

.nb-spacer {
  height: 860rpx;
}

.nb-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8rpx 24rpx;
}

.nb-legend__item {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.nb-dot {
  width: 20rpx;
  height: 20rpx;
  border-radius: 6rpx;
}

.nb-dot--wolf {
  background: var(--camp-wolf);
}

.nb-dot--good {
  background: var(--camp-villager);
}

.nb-dot--god {
  background: var(--camp-god);
}

.nb-card {
  background: var(--panel);
  border: 2rpx solid var(--panel-line);
  border-radius: 32rpx;
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.nb-card--dashed {
  border-style: dashed;
  border-color: var(--line-strong);
}

.nb-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.nb-card__title {
  font-size: 32rpx;
  font-weight: 900;
}

.nb-election {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  padding-bottom: 20rpx;
  border-bottom: 2rpx solid var(--panel-line);
}

.nb-election__title {
  font-size: 28rpx;
  font-weight: 700;
  color: var(--gold);
}

.nb-election__add {
  align-self: flex-start;
}

.nb-link {
  flex-shrink: 0;
  min-width: 88rpx;
  height: 88rpx;
  margin: -20rpx -12rpx -20rpx 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--moon);
}

.nb-group__hint {
  font-size: 24rpx;
  line-height: 1.4;
  color: var(--camp-wolf);
}

/* 票型：和房间里「记录」同一个画法。被投的人一行（头像 + 票条 + 票数），下面是投他的人 */
.nb-bar {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.nb-bar__row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.nb-bar__row--abstain {
  padding-top: 16rpx;
  border-top: 2rpx solid var(--panel-line);
}

.nb-bar__track {
  flex: 1;
  min-width: 0;
  height: 20rpx;
  border-radius: 10rpx;
  background: var(--panel-raised);
  overflow: hidden;
}

.nb-bar__fill {
  height: 20rpx;
  border-radius: 10rpx;
  background: var(--text-2);
}

.nb-bar__fill--top {
  background: var(--gold);
}

.nb-bar__count {
  flex-shrink: 0;
  min-width: 96rpx;
  text-align: right;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--text);
}

.nb-bar__voters {
  padding-left: 104rpx;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 22rpx 0;
}

.nb-bar__voters--flat {
  flex: 1;
  min-width: 0;
  padding-left: 0;
}

.nb-bar__hint {
  padding-left: 104rpx;
}

/* 圆形头像：边框颜色是我在座位上标的；有头像时号码是右下角的小圆，没头像（面杀）时号码在圆里 */
.nb-av {
  position: relative;
  flex-shrink: 0;
  box-sizing: border-box;
  border-radius: 50%;
  border: 4rpx solid var(--line-strong);
  background: var(--panel-raised);
  display: flex;
  align-items: center;
  justify-content: center;
}

.nb-av--m {
  width: 84rpx;
  height: 84rpx;
}

.nb-av--s {
  width: 76rpx;
  height: 76rpx;
}

.nb-av--wolf {
  border-width: 6rpx;
  border-color: var(--camp-wolf);
  background: var(--nb-wolf-bg);
}

.nb-av--good {
  border-width: 6rpx;
  border-color: var(--camp-villager);
  background: var(--nb-good-bg);
}

.nb-av--god {
  border-width: 6rpx;
  border-color: var(--camp-god);
  background: var(--nb-god-bg);
}

.nb-av--off {
  opacity: 0.45;
}

/* 面板里选号码的那一排：一行六个，点得准 */
.nb-targets {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 24rpx 0;
  justify-items: center;
  padding: 8rpx 0 12rpx;
}

.nb-av--pick {
  width: 84rpx;
  height: 84rpx;
}

.nb-av--pick .nb-av__num {
  font-size: 36rpx;
}

.nb-av--picked {
  box-shadow: 0 0 0 6rpx var(--moon);
}

.nb-av__badge {
  position: absolute;
  left: -12rpx;
  top: -12rpx;
  width: 34rpx;
  height: 34rpx;
  box-sizing: border-box;
  border-radius: 50%;
  background: var(--gold);
  border: 2rpx solid var(--ink);
  color: var(--ink);
  font-size: 20rpx;
  font-weight: 900;
  line-height: 30rpx;
  text-align: center;
}

.nb-av__img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
}

.nb-av__num {
  font-size: 32rpx;
  font-weight: 900;
  line-height: 1;
}

.nb-av--m .nb-av__num {
  font-size: 36rpx;
}

.nb-av__no {
  position: absolute;
  right: -10rpx;
  bottom: -8rpx;
  width: 36rpx;
  height: 36rpx;
  box-sizing: border-box;
  border-radius: 50%;
  background: var(--ink);
  border: 2rpx solid var(--line-strong);
  color: var(--text);
  font-size: 20rpx;
  font-weight: 700;
  line-height: 30rpx;
  text-align: center;
}

.nb-entry {
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.nb-entry__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.nb-entry__foot {
  padding-top: 8rpx;
}

.nb-h {
  font-family: var(--serif);
  font-size: 36rpx;
  font-weight: 900;
}

.nb-step {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.nb-step__no {
  font-size: 32rpx;
  font-weight: 900;
  color: var(--gold);
}

.nb-step__text {
  font-size: 30rpx;
  font-weight: 700;
}

.nb-step__count {
  margin-left: auto;
  font-size: 26rpx;
  color: var(--text-2);
}

.nb-picks {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16rpx;
}

.nb-pick {
  height: 96rpx;
  box-sizing: border-box;
  border-radius: 28rpx;
  border: 4rpx solid var(--line-strong);
  background: var(--panel-raised);
  color: var(--text);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4rpx;
}

.nb-pick--tall {
  height: 120rpx;
  background: var(--panel);
  color: var(--text-soft);
}

.nb-pick--gold {
  background: var(--gold);
  border-color: var(--ink);
  color: var(--ink);
  box-shadow: 6rpx 6rpx 0 var(--ink);
}

.nb-pick--done {
  border-color: var(--gold);
}

.nb-pick--on {
  background: var(--text);
  border-color: var(--ink);
  color: var(--ink);
}

.nb-pick--used {
  opacity: 0.45;
}

.nb-pick__no {
  font-size: 36rpx;
  font-weight: 900;
  line-height: 1;
}

.nb-pick__label {
  font-size: 22rpx;
  line-height: 1;
}

.nb-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20rpx;
}

.nb-line--rest {
  align-items: center;
  border-top: 2rpx solid var(--panel-line);
  padding-top: 20rpx;
}

.nb-line__text {
  font-size: 28rpx;
  line-height: 1.5;
}

.nb-line__title {
  font-weight: 700;
  color: var(--moon);
}

.nb-panel {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  max-height: 78vh;
  overflow-y: auto;
  box-sizing: border-box;
  padding: 24rpx 32rpx calc(env(safe-area-inset-bottom) + 24rpx);
  background: var(--panel);
  border-top: 4rpx solid var(--ink);
  border-radius: 48rpx 48rpx 0 0;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.nb-panel::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}

.nb-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.nb-panel__title {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.nb-panel__no {
  font-size: 40rpx;
  font-weight: 900;
}

.nb-panel__hint {
  font-size: 24rpx;
  color: var(--moon);
  text-align: right;
}

.nb-panel__text {
  font-size: 26rpx;
  line-height: 1.5;
  color: var(--text-soft);
}

.nb-label {
  font-size: 24rpx;
  color: var(--text-2);
}

/* 「我猜他是」和下面两排一样大：不再四个拉满一整行 */
.nb-guesses {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.nb-guess {
  height: 64rpx;
  padding: 0 28rpx;
  box-sizing: border-box;
  border-radius: 32rpx;
  border: 3rpx solid var(--line-strong);
  background: var(--panel-raised);
  color: var(--text);
  font-size: 26rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nb-guess--wolf {
  background: var(--nb-wolf-bg);
  border-color: var(--camp-wolf);
}

.nb-guess--good {
  background: var(--nb-good-bg);
  border-color: var(--camp-villager);
}

.nb-guess--god {
  background: var(--nb-god-bg);
  border-color: var(--camp-god);
}

.nb-guess--on {
  border: 3rpx solid var(--ink);
  box-shadow: 3rpx 3rpx 0 var(--ink);
  color: var(--ink);
  font-weight: 700;
}

.nb-guess--on.nb-guess--wolf {
  background: var(--camp-wolf);
}

.nb-guess--on.nb-guess--good {
  background: var(--camp-villager);
}

.nb-guess--on.nb-guess--god {
  background: var(--camp-god);
}

.nb-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

/* 座位面板里的按钮比外面矮一档，面板少挡一点座位 */
.nb-panel .nb-chips {
  gap: 12rpx;
}

.nb-panel .nb-chip {
  height: 64rpx;
  padding: 0 22rpx;
  border-radius: 32rpx;
  font-size: 26rpx;
}

.nb-panel .nb-status__item {
  height: 68rpx;
  border-radius: 24rpx;
  font-size: 24rpx;
}

.nb-panel .nb-status__item--close {
  height: 76rpx;
  font-size: 28rpx;
}

.nb-status {
  margin-top: 8rpx;
  display: flex;
  gap: 12rpx;
}

.nb-status .nb-status__item {
  font-size: 24rpx;
}

.nb-status__item {
  flex: 1;
  min-width: 0;
  height: 80rpx;
  box-sizing: border-box;
  border-radius: 28rpx;
  border: 3rpx solid var(--line-strong);
  color: var(--text-soft);
  font-size: 28rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nb-status__item--on {
  border: 4rpx solid var(--ink);
  background: var(--text);
  color: var(--ink);
  font-weight: 700;
}

.nb-status__item--back {
  flex: none;
  height: 72rpx;
}

.nb-status__item--close {
  flex: none;
  border: 4rpx solid var(--ink);
  background: var(--gold);
  color: var(--ink);
  font-weight: 700;
  box-shadow: 4rpx 4rpx 0 var(--ink);
}
</style>
