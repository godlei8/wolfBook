<script setup>
// 电子法官房间页（新流程房间）。页面只管：拉快照和轮询、导航栏、暂停遮罩、把快照交给当前视图组件。
// 画面上的判断都在 utils/judge/ 下的纯函数里；视图组件放在 ./views/，页面里只嵌一层。
// 旧流程房间（线上旧版小程序建的，autoJudge = false）跳到 room-legacy 的旧主持台。
import { computed, ref } from 'vue'
import { onHide, onLoad, onShareAppMessage, onShow, onUnload } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import LobbyView from './views/LobbyView.vue'
import NightView from './views/NightView.vue'
import PendingView from './views/PendingView.vue'
import SheriffView from './views/SheriffView.vue'
import AnnounceView from './views/AnnounceView.vue'
import SpeechView from './views/SpeechView.vue'
import VoteView from './views/VoteView.vue'
import ResultView from './views/ResultView.vue'
import LogSheet from './views/LogSheet.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { mergePollResult, pollInterval } from '../../utils/judge/room-poll.js'
import { phaseTitle, pickView } from '../../utils/judge/room-view.js'
import { judgeActions, pausedOverlay } from '../../utils/judge/judge-bar.js'
import { hostActions } from '../../utils/judge/day.js'
import { goToUserTab, hasAuthToken } from '../../utils/auth'

const roomId = ref('')
const loadOptions = ref({})
const snapshot = ref(null)
// 板子详情（立绘、背景图、阵容头像），按快照里的 boardId 拉一次
const board = ref(null)
const pollError = ref('')
const actionLoading = ref(false)

// 倒计时用的本地时钟，每秒走一下（只是重画，不发请求）
const now = ref(Date.now())

let pollTimer = null
let clockTimer = null
let active = false
let redirected = false

const view = computed(() => pickView(snapshot.value))
const title = computed(() => phaseTitle(snapshot.value) || '法官房间')
const judgeBar = computed(() => judgeActions(snapshot.value))
const overlay = computed(() => pausedOverlay(snapshot.value))
const hasJudgeBar = computed(() => judgeBar.value.pause || judgeBar.value.resume || judgeBar.value.forceOut)
// 房主的白天推进按钮（有法官时房主就是法官，和暂停 / 点杀放在同一条操作栏）
const hostItems = computed(() => hostActions(snapshot.value))
const hasBar = computed(() => view.value === 'lobby' || hasJudgeBar.value || hostItems.value.length > 0)
// 点杀：先打开座位面板选人，再二次确认
const forceOutOpen = ref(false)
// 对局记录面板（右上角常驻入口，白天夜里都能开）
const logOpen = ref(false)
const forceOutSeats = computed(() => (snapshot.value?.players || []).filter((player) => player.seatNo && player.alive !== false))

function safeDecode(value) {
  if (!value) return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function redirectInviteToLogin() {
  storage.setPendingJudgeInvite({ roomId: roomId.value, boardName: safeDecode(loadOptions.value.boardName || '') })
  uni.showToast({ title: '登录后可继续进入法官房', icon: 'none' })
  setTimeout(() => goToUserTab(), 240)
}

function goLegacy() {
  if (redirected) return
  redirected = true
  stopPolling()
  const query = Object.entries({ ...loadOptions.value, roomId: roomId.value })
    .filter(([, value]) => value !== undefined && value !== '')
    .map(([key, value]) => `${key}=${encodeURIComponent(safeDecode(String(value)))}`)
    .join('&')
  uni.redirectTo({ url: `/pages/judge/room-legacy?${query}` })
}

function applySnapshot(data) {
  if (!data) return
  if (data.autoJudge === false) {
    goLegacy()
    return
  }
  snapshot.value = data
  storage.clearPendingJudgeInvite()
  loadBoard(data.boardId)
}

async function loadBoard(boardId) {
  if (!boardId || board.value?.id === boardId) return
  try {
    board.value = await api.getBoardDetail(boardId)
  } catch (error) {
    board.value = null
  }
}

async function refresh() {
  if (!roomId.value || redirected) return
  if (!hasAuthToken()) {
    stopPolling()
    redirectInviteToLogin()
    return
  }
  let result
  try {
    result = { ok: true, snapshot: await api.getJudgeRoom(roomId.value) }
  } catch (error) {
    result = { ok: false, code: error?.code, message: error?.message }
  }
  const merged = mergePollResult(snapshot.value, result)
  pollError.value = merged.error
  if (result.ok) {
    applySnapshot(merged.snapshot)
  } else if (!snapshot.value && merged.error) {
    uni.showToast({ title: merged.error, icon: 'none' })
  }
}

// setTimeout 链：上一次请求回来才排下一次，网络慢时不会堆积请求。
function schedulePoll() {
  stopPolling()
  if (!active || redirected) return
  const delay = pollInterval(snapshot.value)
  if (!delay) return
  pollTimer = setTimeout(async () => {
    pollTimer = null
    await refresh()
    schedulePoll()
  }, delay)
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function startClock() {
  stopClock()
  now.value = Date.now()
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

function stopClock() {
  if (clockTimer) {
    clearInterval(clockTimer)
    clockTimer = null
  }
}

function keepScreenOn(on) {
  try {
    uni.setKeepScreenOn({ keepScreenOn: on })
  } catch (error) {
    // H5 没有这个接口
  }
}

/** 视图里的提交统一走这里：成功换快照；4002（状态刚变）只刷新不弹错；其他错误弹翻译好的文案。 */
async function runAction(task) {
  if (actionLoading.value) return false
  actionLoading.value = true
  try {
    applySnapshot(await task())
    return true
  } catch (error) {
    if (error?.code === 4002) {
      await refresh()
    } else {
      uni.showToast({ title: error?.message || '操作失败', icon: 'none' })
    }
    return false
  } finally {
    actionLoading.value = false
  }
}

/** 视图组件 emit('act', { kind, ... })，在这里换成接口调用。 */
function onAct(event) {
  const id = snapshot.value?.roomId || roomId.value
  const tasks = {
    join: () => api.joinJudgeRoom(id),
    ready: () => api.toggleJudgeReady(id),
    unready: () => api.toggleJudgeReady(id),
    start: () => api.startJudgeRoom(id),
    rules: () => api.updateJudgeRoomRules(id, event.rules),
    night: () => api.submitJudgeNightAction(id, { actionType: event.actionType, targetSeatNo: event.targetSeatNo }),
    advance: () => api.advanceJudgeRoom(id, event.payload),
    skill: () => api.submitJudgeSkill(id, { decision: event.decision, targetSeatNo: event.targetSeatNo }),
    sheriff: () => api.submitJudgeSheriff(id, event.payload),
    vote: () => api.submitJudgeVote(id, { targetSeatNo: event.targetSeatNo }),
  }
  const task = tasks[event?.kind]
  if (task) runAction(task)
}

function tapHostItem(item) {
  if (item.disabled || actionLoading.value) return
  if (!item.confirm) {
    onAct({ kind: 'advance', payload: item.payload })
    return
  }
  uni.showModal({
    title: item.label,
    content: '强制结束这一局，记为流局？',
    confirmText: '结束',
    success: (result) => {
      if (result.confirm) onAct({ kind: 'advance', payload: item.payload })
    },
  })
}

function pauseOrResume() {
  onAct({ kind: 'advance', payload: { action: judgeBar.value.resume ? 'resume' : 'pause' } })
}

function confirmForceOut(player) {
  forceOutOpen.value = false
  uni.showModal({
    title: '点杀',
    content: `确定让 ${player.seatNo} 号 ${player.nickname || ''} 直接出局？`,
    confirmText: '点杀',
    success: (result) => {
      if (result.confirm) onAct({ kind: 'advance', payload: { action: 'force_out', forceOutSeatNo: player.seatNo } })
    },
  })
}

function judgeSharePayload() {
  const room = snapshot.value
  const boardName = encodeURIComponent(room?.boardName || '')
  return {
    title: room?.boardName ? `房号 ${room.roomCode || ''} · 来「${room.boardName}」入座` : '邀请你加入狼人杀电子法官房间',
    path: `/pages/judge/room?roomId=${room?.roomId || roomId.value}&boardName=${boardName}&fromShare=1`,
    imageUrl: board.value?.backgroundImage || board.value?.coverImage || '',
  }
}

onLoad((options) => {
  loadOptions.value = options || {}
  roomId.value = options?.roomId || ''
  if (!roomId.value) {
    uni.showToast({ title: '缺少房间号', icon: 'none' })
    setTimeout(() => uni.switchTab({ url: '/pages/judge/index' }), 180)
    return
  }
  const boardName = safeDecode(options?.boardName || '')
  if (boardName) {
    storage.setPendingJudgeInvite({ roomId: roomId.value, boardName })
  }
})

onShareAppMessage(() => judgeSharePayload())

onShow(async () => {
  active = true
  keepScreenOn(true)
  startClock()
  await refresh()
  schedulePoll()
})

onHide(() => {
  active = false
  stopPolling()
  stopClock()
  keepScreenOn(false)
})

onUnload(() => {
  active = false
  stopPolling()
  stopClock()
  keepScreenOn(false)
})
</script>

<template>
  <view class="nt-page" :class="{ 'nt-page--bar': hasBar }">
    <NavBar back :title="title">
      <template #side>
        <text v-if="snapshot && view !== 'lobby'" class="jr-logbtn" @tap="logOpen = true">记录</text>
      </template>
    </NavBar>
    <view v-if="pollError" class="nt-gutter">
      <view class="jr-warn">{{ pollError }}</view>
    </view>
    <view v-if="!snapshot" class="jr-loading">正在进入房间…</view>
    <LobbyView v-else-if="view === 'lobby'" :snapshot="snapshot" :board="board" :busy="actionLoading" @act="onAct" />
    <NightView v-else-if="view === 'night'" :snapshot="snapshot" :board="board" :now="now" :busy="actionLoading" @act="onAct" />
    <PendingView v-else-if="view === 'pending'" :snapshot="snapshot" :now="now" :busy="actionLoading" @act="onAct" />
    <SheriffView v-else-if="view === 'sheriff'" :snapshot="snapshot" :now="now" :busy="actionLoading" @act="onAct" />
    <AnnounceView v-else-if="view === 'announce'" :snapshot="snapshot" :busy="actionLoading" @act="onAct" />
    <SpeechView v-else-if="view === 'speech'" :snapshot="snapshot" :now="now" :busy="actionLoading" @act="onAct" />
    <VoteView v-else-if="view === 'vote' || view === 'voteResult'" :snapshot="snapshot" :now="now" :busy="actionLoading" @act="onAct" />
    <ResultView v-else-if="view === 'result'" :snapshot="snapshot" :board="board" @open-log="logOpen = true" />
    <view v-else class="nt-gutter">
      <view class="nt-panel jr-placeholder">
        <text class="jr-placeholder__title">{{ snapshot.boardName }}</text>
        <text class="jr-placeholder__phase">{{ title }}</text>
      </view>
    </view>

    <!-- 法官暂停：所有人同一张遮罩，倒计时停表；法官的操作栏浮在遮罩上面 -->
    <view v-if="overlay" class="jr-pause">
      <text class="jr-pause__moon">☾</text>
      <text class="jr-pause__title serif">{{ overlay.title }}</text>
      <text class="jr-pause__hint">{{ overlay.hint }}</text>
    </view>

    <view v-if="view !== 'lobby' && (hasJudgeBar || hostItems.length)" class="nt-bar jr-judgebar">
      <view
        v-for="item in hostItems"
        :key="item.action"
        class="nt-btn jr-judgebar__btn"
        :class="[item.confirm ? 'nt-btn--ghost' : 'nt-btn--gold', { 'nt-btn--disabled': item.disabled || actionLoading }]"
        @tap="tapHostItem(item)"
      >{{ item.label }}</view>
      <view
        v-if="hasJudgeBar"
        class="nt-btn jr-judgebar__btn"
        :class="[judgeBar.resume ? 'nt-btn--gold' : 'nt-btn--ghost', { 'nt-btn--disabled': actionLoading }]"
        @tap="pauseOrResume"
      >{{ judgeBar.resume ? '继续计时' : '暂停计时' }}</view>
      <view v-if="judgeBar.forceOut" class="nt-btn nt-btn--ghost jr-judgebar__btn" @tap="forceOutOpen = true">点杀</view>
    </view>

    <LogSheet v-if="logOpen && snapshot" :snapshot="snapshot" @close="logOpen = false" />

    <view v-if="forceOutOpen" class="jr-sheet" @tap="forceOutOpen = false">
      <view class="nt-panel jr-sheet__body" @tap.stop>
        <text class="nt-h2">点杀谁？</text>
        <view class="jr-sheet__seats">
          <view v-for="player in forceOutSeats" :key="player.seatNo" class="jr-sheet__seat" @tap="confirmForceOut(player)">
            <text class="jr-sheet__no serif">{{ player.seatNo }}</text>
            <text class="jr-sheet__name">{{ player.nickname }}</text>
          </view>
        </view>
        <view class="nt-btn nt-btn--ghost" @tap="forceOutOpen = false">取消</view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.jr-warn {
  margin: 16rpx 0;
  padding: 16rpx 24rpx;
  border-radius: 16rpx;
  background: rgba(214, 92, 92, 0.16);
  color: var(--text);
  font-size: 24rpx;
}

.jr-placeholder {
  margin-top: 24rpx;
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

.jr-placeholder__title {
  font-family: var(--serif);
  font-size: 36rpx;
  color: var(--text);
}

.jr-placeholder__phase {
  font-size: 26rpx;
  color: var(--text-2);
}

.jr-pause {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 40;
  background: rgba(7, 6, 10, 0.86);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
}

.jr-pause__moon {
  color: var(--gold);
  font-size: 96rpx;
}

.jr-pause__title {
  color: var(--text);
  font-size: 48rpx;
  font-weight: 900;
}

.jr-pause__hint {
  color: var(--text-2);
  font-size: 26rpx;
}

.jr-judgebar {
  z-index: 50;
  gap: 20rpx;
}

.jr-judgebar__btn {
  flex: 1;
  min-width: 0;
  padding-left: 8rpx;
  padding-right: 8rpx;
  font-size: 26rpx;
  white-space: nowrap;
}

.jr-sheet {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  background: rgba(7, 6, 10, 0.6);
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.jr-sheet__body {
  margin: 0 var(--page-gutter) calc(env(safe-area-inset-bottom) + 32rpx);
  padding: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.jr-sheet__seats {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.jr-sheet__seat {
  width: 128rpx;
  padding: 12rpx 0;
  border-radius: 20rpx;
  background: var(--panel-raised);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
}

.jr-sheet__no {
  color: var(--gold);
  font-size: 36rpx;
  font-weight: 900;
}

.jr-sheet__name {
  max-width: 112rpx;
  color: var(--text-2);
  font-size: 20rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.jr-logbtn {
  padding: 8rpx 20rpx;
  border-radius: 24rpx;
  background: var(--panel-raised);
  color: var(--text);
  font-size: 24rpx;
}

.jr-loading {
  padding: 120rpx 0;
  text-align: center;
  color: var(--text-2);
  font-size: 26rpx;
}
</style>
