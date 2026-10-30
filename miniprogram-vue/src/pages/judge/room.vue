<script setup>
// 电子法官房间页（新流程房间）。页面只管：拉快照和轮询、导航栏、暂停遮罩、把快照交给当前视图组件。
// 画面上的判断都在 utils/judge/ 下的纯函数里；视图组件放在 ./views/，页面里只嵌一层。
// 旧流程房间（线上旧版小程序建的，autoJudge = false）跳到 room-legacy 的旧主持台。
import { computed, ref } from 'vue'
import { onHide, onLoad, onShareAppMessage, onShow, onUnload } from '@dcloudio/uni-app'
import NavBar from '../../components/NavBar.vue'
import LobbyView from './views/LobbyView.vue'
import api from '../../services/api'
import storage from '../../services/storage'
import { mergePollResult, pollInterval } from '../../utils/judge/room-poll.js'
import { phaseTitle, pickView } from '../../utils/judge/room-view.js'
import { goToUserTab, hasAuthToken } from '../../utils/auth'

const roomId = ref('')
const loadOptions = ref({})
const snapshot = ref(null)
// 板子详情（立绘、背景图、阵容头像），按快照里的 boardId 拉一次
const board = ref(null)
const pollError = ref('')
const actionLoading = ref(false)

let pollTimer = null
let active = false
let redirected = false

const view = computed(() => pickView(snapshot.value))
const title = computed(() => phaseTitle(snapshot.value) || '法官房间')

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
  }
  const task = tasks[event?.kind]
  if (task) runAction(task)
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
  await refresh()
  schedulePoll()
})

onHide(() => {
  active = false
  stopPolling()
  keepScreenOn(false)
})

onUnload(() => {
  active = false
  stopPolling()
  keepScreenOn(false)
})
</script>

<template>
  <view class="nt-page" :class="{ 'nt-page--bar': view === 'lobby' }">
    <NavBar back :title="title" />
    <view v-if="pollError" class="nt-gutter">
      <view class="jr-warn">{{ pollError }}</view>
    </view>
    <view v-if="!snapshot" class="jr-loading">正在进入房间…</view>
    <LobbyView v-else-if="view === 'lobby'" :snapshot="snapshot" :board="board" :busy="actionLoading" @act="onAct" />
    <view v-else class="nt-gutter">
      <view class="nt-panel jr-placeholder">
        <text class="jr-placeholder__title">{{ snapshot.boardName }}</text>
        <text class="jr-placeholder__phase">{{ title }}</text>
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

.jr-loading {
  padding: 120rpx 0;
  text-align: center;
  color: var(--text-2);
  font-size: 26rpx;
}
</style>
